package com.rescuemesh.ai.maps

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Status of an offline vector map region.
 */
@Serializable
enum class OfflineRegionStatus {
    AVAILABLE,      // Catalog item available for download
    QUEUED,         // Enqueued in WorkManager awaiting unmetered Wi-Fi
    DOWNLOADING,    // Streaming download actively in progress
    DOWNLOADED,     // Verified on disk and ready for 100% offline rendering
    FAILED          // Download, network, or integrity verification failed
}

/**
 * Metadata representation of a downloadable offline vector map package.
 *
 * @property regionId Unique tactical identifier (e.g., "bangalore_urban", "wayanad_disaster_zone")
 * @property name Human-readable tactical sector label
 * @property description Detailed coverage description
 * @property minLat Minimum latitude (Southern boundary, -90.0 to 90.0)
 * @property maxLat Maximum latitude (Northern boundary, -90.0 to 90.0)
 * @property minLon Minimum longitude (Western boundary, -180.0 to 180.0)
 * @property maxLon Maximum longitude (Eastern boundary, -180.0 to 180.0)
 * @property minZoom Minimum tile zoom level (e.g., 0)
 * @property maxZoom Maximum tile zoom level (e.g., 16 for high-resolution building footprints)
 * @property fileSizeBytes Estimated or verified size on disk in bytes
 * @property downloadTimestamp Monotonic/wall-clock epoch milliseconds when download finished
 * @property status Current lifecycle state of this region
 * @property downloadUrl Authorized HTTPS tile endpoint (e.g., openfreemap.org or protomaps.com)
 * @property fileName App-private persistent file name (e.g., "bangalore_urban.mbtiles")
 */
@Serializable
data class OfflineRegionModel(
    val regionId: String,
    val name: String,
    val description: String = "",
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val minZoom: Int = 0,
    val maxZoom: Int = 16,
    val fileSizeBytes: Long = 0L,
    val downloadTimestamp: Long? = null,
    val status: OfflineRegionStatus = OfflineRegionStatus.AVAILABLE,
    val downloadUrl: String = "",
    val fileName: String = "${regionId}.mbtiles"
) {
    init {
        require(minLat <= maxLat) { "minLat ($minLat) must be <= maxLat ($maxLat)" }
        require(minLat in -90.0..90.0 && maxLat in -90.0..90.0) {
            "Latitude bounds must be within [-90.0, 90.0]"
        }
        require(minLon in -180.0..180.0 && maxLon in -180.0..180.0) {
            "Longitude bounds must be within [-180.0, 180.0]"
        }
        require(fileSizeBytes >= 0L) { "fileSizeBytes must be non-negative" }
    }

    /**
     * Determines whether the given WGS-84 coordinate falls within this region's bounding box.
     * Cleanly handles antimeridian (+/-180 deg) boundaries.
     */
    fun contains(latitude: Double, longitude: Double): Boolean {
        if (latitude < minLat || latitude > maxLat) {
            return false
        }
        return if (minLon <= maxLon) {
            longitude in minLon..maxLon
        } else {
            // Antimeridian spanning bounding box (e.g. minLon = 170.0, maxLon = -170.0)
            longitude >= minLon || longitude <= maxLon
        }
    }

    /**
     * Returns true if this region is verified and marked ready for offline rendering.
     */
    val isDownloaded: Boolean
        get() = status == OfflineRegionStatus.DOWNLOADED

    /**
     * Formats bytes into a clean tactical human-readable string (e.g., "45.2 MB").
     */
    fun formatSize(): String {
        val mb = fileSizeBytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            val gb = mb / 1024.0
            String.format(Locale.US, "%.2f GB", gb)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }
}
