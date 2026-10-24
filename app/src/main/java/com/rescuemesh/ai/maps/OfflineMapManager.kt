package com.rescuemesh.ai.maps

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Result of an offline map region download request.
 */
sealed class DownloadResult {
    data class Enqueued(val workId: UUID, val regionId: String) : DownloadResult()
    data class AlreadyDownloaded(val regionId: String) : DownloadResult()
    data class QuotaExceeded(val requiredBytes: Long, val availableBytes: Long) : DownloadResult()
    data class RegionNotFound(val regionId: String) : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}

/**
 * Manager for offline vector map packages, storage quotas, WorkManager background downloads,
 * and zero-dependency Situational Canvas fallback.
 *
 * Invariants:
 * - App-private persistent storage: context.filesDir.resolve("offline_maps/")
 * - Storage Quota: Configurable maximum (default 1.0 GB), real-time usage calculation
 * - Zero-Dependency Fallback: hasOfflineMapFor(lat, lon) determines vector readiness.
 *   If tiles are absent or corrupt, fallback to SituationalCanvasView is automatic.
 *   Emergency reporting and tactical coordination must NEVER be blocked on missing map data.
 */
class OfflineMapManager(
    val baseDir: File,
    private val workManager: WorkManager? = null,
    initialMaxQuotaBytes: Long = DEFAULT_MAX_QUOTA_BYTES
) {
    /**
     * Secondary constructor for standard Android runtime contexts.
     */
    constructor(
        context: Context,
        workManager: WorkManager? = try { WorkManager.getInstance(context) } catch (_: Exception) { null },
        initialMaxQuotaBytes: Long = DEFAULT_MAX_QUOTA_BYTES
    ) : this(
        baseDir = context.filesDir.resolve(OFFLINE_MAPS_DIR_NAME),
        workManager = workManager,
        initialMaxQuotaBytes = initialMaxQuotaBytes
    )

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    val offlineMapsDir: File = baseDir
    val stylesDir: File = baseDir.resolve("styles")
    private val registryFile: File = baseDir.resolve("regions.json")

    private var _maxQuotaBytes: Long = initialMaxQuotaBytes
    private val regionsMap = LinkedHashMap<String, OfflineRegionModel>()
    private val _regionsFlow = MutableStateFlow<List<OfflineRegionModel>>(emptyList())
    val regionsFlow: StateFlow<List<OfflineRegionModel>> = _regionsFlow.asStateFlow()

    init {
        ensureDirectories()
        loadDefaultCatalog()
        loadRegistry()
        scanAndSyncStorage()
        ensureTacticalStyle()
    }

    private fun ensureDirectories() {
        if (!offlineMapsDir.exists()) offlineMapsDir.mkdirs()
        if (!stylesDir.exists()) stylesDir.mkdirs()
    }

    // =========================================================================
    // Catalog & Region Management
    // =========================================================================

    private fun loadDefaultCatalog() {
        DEFAULT_REGIONS.forEach { region ->
            regionsMap[region.regionId] = region
        }
    }

    @Synchronized
    private fun loadRegistry() {
        if (!registryFile.exists()) {
            saveRegistry()
            return
        }
        try {
            val content = registryFile.readText()
            if (content.isNotBlank()) {
                val savedList = json.decodeFromString<List<OfflineRegionModel>>(content)
                savedList.forEach { saved ->
                    regionsMap[saved.regionId] = saved
                }
            }
        } catch (_: Exception) {
            // If registry is corrupt, fall back to default catalog
            loadDefaultCatalog()
        }
    }

    @Synchronized
    private fun saveRegistry() {
        try {
            val list = regionsMap.values.toList()
            val text = json.encodeToString(list)
            registryFile.writeText(text)
        } catch (_: Exception) {
            // Best effort persistence
        }
    }

    /**
     * Returns a snapshot of all cataloged offline vector map regions.
     */
    @Synchronized
    fun getAllRegions(): List<OfflineRegionModel> {
        return regionsMap.values.toList()
    }

    /**
     * Looks up a specific region by its identifier.
     */
    @Synchronized
    fun getRegion(regionId: String): OfflineRegionModel? {
        return regionsMap[regionId]
    }

    /**
     * Returns all regions currently downloaded and physically verified on disk.
     */
    @Synchronized
    fun getDownloadedRegions(): List<OfflineRegionModel> {
        return regionsMap.values.filter { it.status == OfflineRegionStatus.DOWNLOADED }
    }

    /**
     * Registers or updates a region in the catalog.
     */
    @Synchronized
    fun registerRegion(region: OfflineRegionModel) {
        regionsMap[region.regionId] = region
        saveRegistry()
        scanAndSyncStorage()
    }

    // =========================================================================
    // Storage & Quota Management
    // =========================================================================

    /**
     * Returns the configured maximum storage quota in bytes.
     */
    fun getMaxQuotaBytes(): Long = _maxQuotaBytes

    /**
     * Updates the maximum storage quota.
     */
    @Synchronized
    fun setMaxQuotaBytes(newQuotaBytes: Long) {
        require(newQuotaBytes > 0L) { "Quota must be positive" }
        _maxQuotaBytes = newQuotaBytes
    }

    /**
     * Calculates the total physical bytes used by downloaded offline maps on disk.
     * Excludes temporary files and hidden dotfiles.
     */
    fun getTotalStorageUsedBytes(): Long {
        if (!offlineMapsDir.exists() || !offlineMapsDir.isDirectory) return 0L
        return offlineMapsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".mbtiles") }
            ?.sumOf { it.length() } ?: 0L
    }

    /**
     * Returns the remaining storage quota available in bytes before reaching limit.
     */
    fun getRemainingQuotaBytes(): Long {
        val used = getTotalStorageUsedBytes()
        return (_maxQuotaBytes - used).coerceAtLeast(0L)
    }

    /**
     * Computes percentage of quota used (0.0% to 100.0%).
     */
    fun getQuotaUsagePercentage(): Float {
        val used = getTotalStorageUsedBytes()
        if (_maxQuotaBytes <= 0L) return 100.0f
        return ((used.toDouble() / _maxQuotaBytes.toDouble()) * 100.0).toFloat().coerceIn(0.0f, 100.0f)
    }

    /**
     * Verifies if additionalBytes can be stored without exceeding the maximum quota.
     */
    fun hasSufficientQuotaFor(additionalBytes: Long): Boolean {
        return (getTotalStorageUsedBytes() + additionalBytes) <= _maxQuotaBytes
    }

    /**
     * Cleans up orphaned or incomplete .tmp files left by aborted downloads.
     */
    @Synchronized
    fun cleanOrphanedTempFiles(): Int {
        if (!offlineMapsDir.exists()) return 0
        var cleanedCount = 0
        offlineMapsDir.listFiles()?.forEach { file ->
            if (file.isFile && (file.name.endsWith(".tmp") || file.name.contains(".tmp_"))) {
                if (file.delete()) cleanedCount++
            }
        }
        return cleanedCount
    }

    // =========================================================================
    // Storage Synchronization & Integrity Verification
    // =========================================================================

    /**
     * Synchronizes in-memory catalog statuses with physical files on disk.
     * Reverts missing files to AVAILABLE, marks present non-zero files as DOWNLOADED.
     */
    @Synchronized
    fun scanAndSyncStorage() {
        cleanOrphanedTempFiles()
        var mutated = false

        regionsMap.values.toList().forEach { region ->
            val targetFile = File(offlineMapsDir, region.fileName)
            val fileExists = targetFile.exists() && targetFile.length() > 0L

            if (fileExists && region.status != OfflineRegionStatus.DOWNLOADED) {
                val updated = region.copy(
                    status = OfflineRegionStatus.DOWNLOADED,
                    fileSizeBytes = targetFile.length(),
                    downloadTimestamp = region.downloadTimestamp ?: targetFile.lastModified()
                )
                regionsMap[region.regionId] = updated
                mutated = true
            } else if (!fileExists && region.status == OfflineRegionStatus.DOWNLOADED) {
                val updated = region.copy(
                    status = OfflineRegionStatus.AVAILABLE,
                    downloadTimestamp = null
                )
                regionsMap[region.regionId] = updated
                mutated = true
            }
        }

        if (mutated) {
            saveRegistry()
        }
        _regionsFlow.value = regionsMap.values.toList()
    }

    /**
     * Explicitly marks a region as downloaded (e.g. upon WorkManager completion or manual import).
     */
    @Synchronized
    fun markRegionDownloaded(regionId: String, fileSizeBytes: Long, timestamp: Long = System.currentTimeMillis()): Boolean {
        val region = regionsMap[regionId] ?: return false
        val updated = region.copy(
            status = OfflineRegionStatus.DOWNLOADED,
            fileSizeBytes = fileSizeBytes,
            downloadTimestamp = timestamp
        )
        regionsMap[regionId] = updated
        saveRegistry()
        _regionsFlow.value = regionsMap.values.toList()
        return true
    }

    // =========================================================================
    // WorkManager Background Download & Deletion
    // =========================================================================

    /**
     * Enqueues a background download for the specified region using WorkManager.
     * Enforces unmetered Wi-Fi constraint and storage quota validation.
     */
    @Synchronized
    fun enqueueDownload(regionId: String): DownloadResult {
        val region = regionsMap[regionId] ?: return DownloadResult.RegionNotFound(regionId)

        val targetFile = File(offlineMapsDir, region.fileName)
        if (targetFile.exists() && targetFile.length() > 0L) {
            markRegionDownloaded(regionId, targetFile.length())
            return DownloadResult.AlreadyDownloaded(regionId)
        }

        if (!hasSufficientQuotaFor(region.fileSizeBytes)) {
            return DownloadResult.QuotaExceeded(
                requiredBytes = region.fileSizeBytes,
                availableBytes = getRemainingQuotaBytes()
            )
        }

        val wm = workManager ?: return DownloadResult.Error("WorkManager instance not available")

        val workRequest = MapDownloadWorker.createWorkRequest(region)
        wm.enqueueUniqueWork(
            "download_map_${region.regionId}",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        val updated = region.copy(status = OfflineRegionStatus.QUEUED)
        regionsMap[regionId] = updated
        saveRegistry()
        _regionsFlow.value = regionsMap.values.toList()

        return DownloadResult.Enqueued(workRequest.id, regionId)
    }

    /**
     * Deletes a downloaded offline map package and frees storage.
     */
    @Synchronized
    fun deleteRegion(regionId: String): Boolean {
        val region = regionsMap[regionId] ?: return false
        val targetFile = File(offlineMapsDir, region.fileName)
        val fileDeleted = if (targetFile.exists()) targetFile.delete() else true

        val updated = region.copy(
            status = OfflineRegionStatus.AVAILABLE,
            downloadTimestamp = null
        )
        regionsMap[regionId] = updated
        saveRegistry()
        _regionsFlow.value = regionsMap.values.toList()
        return fileDeleted
    }

    // =========================================================================
    // Zero-Dependency Canvas Fallback Architecture
    // =========================================================================

    /**
     * Verifies if a valid, physically present offline vector map package covers
     * the specified geographic coordinate.
     *
     * @param latitude Target latitude in decimal degrees
     * @param longitude Target longitude in decimal degrees
     * @return true ONLY if a downloaded region encloses the coordinate AND the file exists on disk.
     */
    @Synchronized
    fun hasOfflineMapFor(latitude: Double, longitude: Double): Boolean {
        return regionsMap.values.any { region ->
            if (region.status == OfflineRegionStatus.DOWNLOADED && region.contains(latitude, longitude)) {
                val file = File(offlineMapsDir, region.fileName)
                file.exists() && file.length() > 0L
            } else {
                false
            }
        }
    }

    /**
     * Retrieves the best-matching downloaded offline region for a coordinate, or null if unmapped.
     */
    @Synchronized
    fun getOfflineMapFor(latitude: Double, longitude: Double): OfflineRegionModel? {
        return regionsMap.values.firstOrNull { region ->
            if (region.status == OfflineRegionStatus.DOWNLOADED && region.contains(latitude, longitude)) {
                val file = File(offlineMapsDir, region.fileName)
                file.exists() && file.length() > 0L
            } else {
                false
            }
        }
    }

    /**
     * Decides whether the tactical UI must fall back to the Situational Canvas (radar).
     * Emergency reporting and tactical coordination must NEVER be blocked on missing map data.
     */
    fun shouldFallbackToCanvas(latitude: Double, longitude: Double): Boolean {
        return !hasOfflineMapFor(latitude, longitude)
    }

    // =========================================================================
    // Offline Tactical Vector Style Generation
    // =========================================================================

    /**
     * Generates and writes the local offline dark tactical vector map style.
     */
    private fun ensureTacticalStyle() {
        val styleFile = File(stylesDir, TACTICAL_DARK_STYLE_FILENAME)
        if (!styleFile.exists()) {
            styleFile.writeText(buildTacticalDarkStyleJson())
        }
    }

    /**
     * Retrieves the File path of the dark tactical offline vector style.
     */
    fun getTacticalStyleFile(): File {
        ensureTacticalStyle()
        return File(stylesDir, TACTICAL_DARK_STYLE_FILENAME)
    }

    /**
     * Produces a valid offline MapLibre GL style specification in dark tactical theme.
     * Complies with Dark Navy palette (#0D1117, surface #161B22, amber #FFB300).
     */
    fun buildTacticalDarkStyleJson(): String {
        return """
        {
          "version": 8,
          "name": "RescueMesh Dark Tactical Offline",
          "metadata": {
            "rescuemesh:offline": true,
            "rescuemesh:palette": "dark_tactical"
          },
          "sources": {
            "openmaptiles": {
              "type": "vector",
              "url": "mbtiles://offline_maps/local.mbtiles"
            }
          },
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": {
                "background-color": "#0D1117"
              }
            },
            {
              "id": "water",
              "type": "fill",
              "source": "openmaptiles",
              "source-layer": "water",
              "paint": {
                "fill-color": "#162B4D"
              }
            },
            {
              "id": "landuse_residential",
              "type": "fill",
              "source": "openmaptiles",
              "source-layer": "landuse",
              "paint": {
                "fill-color": "#161B22",
                "fill-opacity": 0.8
              }
            },
            {
              "id": "roads",
              "type": "line",
              "source": "openmaptiles",
              "source-layer": "transportation",
              "paint": {
                "line-color": "#30363D",
                "line-width": 1.5
              }
            },
            {
              "id": "buildings",
              "type": "fill",
              "source": "openmaptiles",
              "source-layer": "building",
              "paint": {
                "fill-color": "#21262D",
                "fill-outline-color": "#484F58"
              }
            }
          ]
        }
        """.trimIndent()
    }

    companion object {
        const val OFFLINE_MAPS_DIR_NAME = "offline_maps"
        const val TACTICAL_DARK_STYLE_FILENAME = "tactical_dark_style.json"

        // Default 1.0 GB maximum storage quota
        const val DEFAULT_MAX_QUOTA_BYTES = 1024L * 1024L * 1024L // 1,073,741,824 bytes

        /**
         * Pre-configured emergency operational disaster sectors for offline field deployments.
         */
        val DEFAULT_REGIONS = listOf(
            OfflineRegionModel(
                regionId = "bangalore_urban",
                name = "Bangalore Urban Sector",
                description = "Primary urban deployment sector and tactical coordination zone",
                minLat = 12.834,
                maxLat = 13.143,
                minLon = 77.460,
                maxLon = 77.750,
                fileSizeBytes = 52_428_800L, // 50 MB
                downloadUrl = "https://openfreemap.org/tiles/bangalore_urban.mbtiles",
                fileName = "bangalore_urban.mbtiles"
            ),
            OfflineRegionModel(
                regionId = "wayanad_disaster_zone",
                name = "Wayanad Landslide Emergency Sector",
                description = "Meppadi / Chooralmala disaster corridor and access routes",
                minLat = 11.450,
                maxLat = 11.650,
                minLon = 76.050,
                maxLon = 76.250,
                fileSizeBytes = 31_457_280L, // 30 MB
                downloadUrl = "https://openfreemap.org/tiles/wayanad_disaster_zone.mbtiles",
                fileName = "wayanad_disaster_zone.mbtiles"
            ),
            OfflineRegionModel(
                regionId = "himalayan_sector_1",
                name = "Chamoli / Joshimath Flash Flood Zone",
                description = "High-altitude disaster valley and mountain pass access roads",
                minLat = 30.450,
                maxLat = 30.700,
                minLon = 79.450,
                maxLon = 79.750,
                fileSizeBytes = 41_943_040L, // 40 MB
                downloadUrl = "https://openfreemap.org/tiles/himalayan_sector_1.mbtiles",
                fileName = "himalayan_sector_1.mbtiles"
            ),
            OfflineRegionModel(
                regionId = "chennai_coastal",
                name = "Chennai Coastal Flood Basin",
                description = "Low-lying coastal storm surge and river overflow sector",
                minLat = 12.900,
                maxLat = 13.200,
                minLon = 80.100,
                maxLon = 80.350,
                fileSizeBytes = 62_914_560L, // 60 MB
                downloadUrl = "https://openfreemap.org/tiles/chennai_coastal.mbtiles",
                fileName = "chennai_coastal.mbtiles"
            ),
            OfflineRegionModel(
                regionId = "delhi_ncr",
                name = "Delhi NCR Emergency Sector",
                description = "Yamuna floodplains and metropolitan rescue corridor",
                minLat = 28.400,
                maxLat = 28.880,
                minLon = 76.840,
                maxLon = 77.350,
                fileSizeBytes = 73_400_320L, // 70 MB
                downloadUrl = "https://openfreemap.org/tiles/delhi_ncr.mbtiles",
                fileName = "delhi_ncr.mbtiles"
            )
        )
    }
}
