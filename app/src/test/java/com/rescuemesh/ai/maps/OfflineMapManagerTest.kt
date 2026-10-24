package com.rescuemesh.ai.maps

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OfflineMapManagerTest {

    private lateinit var tempDir: File
    private lateinit var mapManager: OfflineMapManager

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("rescuemesh_maps_test").toFile()
        mapManager = OfflineMapManager(
            baseDir = tempDir,
            workManager = null,
            initialMaxQuotaBytes = 500 * 1024 * 1024L // 500 MB quota for test
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testDefaultCatalog_containsStandardDisasterSectors() {
        val regions = mapManager.getAllRegions()
        assertTrue("Catalog should contain default disaster sectors", regions.size >= 5)

        val bangalore = mapManager.getRegion("bangalore_urban")
        assertNotNull(bangalore)
        assertEquals("Bangalore Urban Sector", bangalore?.name)
        assertEquals("https://openfreemap.org/tiles/bangalore_urban.mbtiles", bangalore?.downloadUrl)

        val wayanad = mapManager.getRegion("wayanad_disaster_zone")
        assertNotNull(wayanad)
        assertEquals("Wayanad Landslide Emergency Sector", wayanad?.name)
    }

    @Test
    fun testQuotaManagement_initialUsageAndCalculation() {
        assertEquals(0L, mapManager.getTotalStorageUsedBytes())
        assertEquals(500 * 1024 * 1024L, mapManager.getMaxQuotaBytes())
        assertEquals(500 * 1024 * 1024L, mapManager.getRemainingQuotaBytes())
        assertEquals(0.0f, mapManager.getQuotaUsagePercentage(), 0.01f)

        // Write a 50 MB synthetic tile file directly
        val sectorFile = File(tempDir, "bangalore_urban.mbtiles")
        val fakeBytes = ByteArray(50 * 1024 * 1024) { 0x42 }
        sectorFile.writeBytes(fakeBytes)

        val totalUsed = mapManager.getTotalStorageUsedBytes()
        assertEquals(50 * 1024 * 1024L, totalUsed)
        assertEquals(450 * 1024 * 1024L, mapManager.getRemainingQuotaBytes())
        assertEquals(10.0f, mapManager.getQuotaUsagePercentage(), 0.05f)

        assertTrue(mapManager.hasSufficientQuotaFor(100 * 1024 * 1024L))
        assertFalse(mapManager.hasSufficientQuotaFor(460 * 1024 * 1024L))
    }

    @Test
    fun testQuotaManagement_setCustomQuota() {
        mapManager.setMaxQuotaBytes(1024L * 1024L * 1024L) // 1 GB
        assertEquals(1024L * 1024L * 1024L, mapManager.getMaxQuotaBytes())

        val sectorFile = File(tempDir, "test.mbtiles")
        sectorFile.writeBytes(ByteArray(256 * 1024 * 1024) { 0x11 }) // 256 MB

        assertEquals(25.0f, mapManager.getQuotaUsagePercentage(), 0.05f)
    }

    @Test
    fun testHasOfflineMapFor_returnsFalseWhenTilesAbsent() {
        // Coordinate in Bangalore (12.9716, 77.5946)
        // Region is in catalog but status is AVAILABLE and no file exists
        assertFalse(mapManager.hasOfflineMapFor(12.9716, 77.5946))
        assertNull(mapManager.getOfflineMapFor(12.9716, 77.5946))
        assertTrue("Should fallback to Situational Canvas when vector tiles missing",
            mapManager.shouldFallbackToCanvas(12.9716, 77.5946))
    }

    @Test
    fun testHasOfflineMapFor_returnsFalseWhenFileCorruptedOrZeroByte() {
        // Create zero-byte file
        val file = File(tempDir, "bangalore_urban.mbtiles")
        file.writeBytes(ByteArray(0))

        mapManager.markRegionDownloaded("bangalore_urban", fileSizeBytes = 0L)
        assertFalse("Zero-byte file must be rejected as offline map",
            mapManager.hasOfflineMapFor(12.9716, 77.5946))
        assertTrue(mapManager.shouldFallbackToCanvas(12.9716, 77.5946))
    }

    @Test
    fun testHasOfflineMapFor_returnsTrueWhenDownloadedAndValidFileExists() {
        val file = File(tempDir, "bangalore_urban.mbtiles")
        file.writeBytes("VALID_VECTOR_MBTILES_PAYLOAD".toByteArray())

        mapManager.markRegionDownloaded("bangalore_urban", fileSizeBytes = file.length())

        assertTrue(mapManager.hasOfflineMapFor(12.9716, 77.5946))
        val matched = mapManager.getOfflineMapFor(12.9716, 77.5946)
        assertNotNull(matched)
        assertEquals("bangalore_urban", matched?.regionId)
        assertFalse("Should NOT fallback to Canvas when real offline map is ready",
            mapManager.shouldFallbackToCanvas(12.9716, 77.5946))

        // Coordinates outside Bangalore sector should still return false
        assertFalse(mapManager.hasOfflineMapFor(28.6139, 77.2090)) // Delhi coordinates
        assertTrue(mapManager.shouldFallbackToCanvas(28.6139, 77.2090))
    }

    @Test
    fun testDeleteRegion_deletesFileAndReclaimsQuota() {
        val file = File(tempDir, "bangalore_urban.mbtiles")
        file.writeBytes(ByteArray(10 * 1024 * 1024) { 0x55 })

        mapManager.markRegionDownloaded("bangalore_urban", file.length())
        assertEquals(1, mapManager.getDownloadedRegions().size)
        assertTrue(file.exists())

        val deleted = mapManager.deleteRegion("bangalore_urban")
        assertTrue(deleted)
        assertFalse(file.exists())
        assertEquals(0, mapManager.getDownloadedRegions().size)
        assertEquals(OfflineRegionStatus.AVAILABLE, mapManager.getRegion("bangalore_urban")?.status)
        assertEquals(0L, mapManager.getTotalStorageUsedBytes())
    }

    @Test
    fun testCleanOrphanedTempFiles() {
        val tmpFile1 = File(tempDir, "bangalore_urban.mbtiles.tmp")
        val tmpFile2 = File(tempDir, "sector.mbtiles.tmp_12345")
        val permanentFile = File(tempDir, "bangalore_urban.mbtiles")

        tmpFile1.writeText("incomplete 1")
        tmpFile2.writeText("incomplete 2")
        permanentFile.writeText("permanent")

        val cleaned = mapManager.cleanOrphanedTempFiles()
        assertEquals(2, cleaned)
        assertFalse(tmpFile1.exists())
        assertFalse(tmpFile2.exists())
        assertTrue(permanentFile.exists())
    }

    @Test
    fun testScanAndSyncStorage_reconcilesDiskState() {
        // Manually place a valid sector file on disk
        val file = File(tempDir, "wayanad_disaster_zone.mbtiles")
        file.writeBytes(ByteArray(1024) { 0x77 })

        // Initial in-memory state is AVAILABLE
        assertEquals(OfflineRegionStatus.AVAILABLE, mapManager.getRegion("wayanad_disaster_zone")?.status)

        // Trigger scan
        mapManager.scanAndSyncStorage()

        val updated = mapManager.getRegion("wayanad_disaster_zone")
        assertEquals(OfflineRegionStatus.DOWNLOADED, updated?.status)
        assertEquals(1024L, updated?.fileSizeBytes)
    }

    @Test
    fun testTacticalStyleJson_containsTacticalDarkTokens() {
        val styleFile = mapManager.getTacticalStyleFile()
        assertTrue(styleFile.exists())

        val content = styleFile.readText()
        assertTrue(content.contains("#0D1117")) // Tactical background
        assertTrue(content.contains("#161B22")) // Dark tactical surface
        assertTrue(content.contains("openmaptiles"))
    }

    @Test
    fun testCreateGpsAreaRegion_accurateCosineScaling_acrossLatitudes() {
        val radiusMeters = 1000f

        // 1. Bangalore (Mid-latitude: 12.9716°N, 77.5946°E)
        val bangaloreRegion = mapManager.createGpsAreaRegion(
            latitude = 12.9716,
            longitude = 77.5946,
            radiusMeters = radiusMeters
        )

        val bLatSpanDeg = bangaloreRegion.maxLat - bangaloreRegion.minLat
        val bLonSpanDeg = bangaloreRegion.maxLon - bangaloreRegion.minLon
        val bPhysicalLatMeters = bLatSpanDeg * OfflineRegionModel.METERS_PER_LAT_DEGREE
        val bPhysicalLonMeters = bLonSpanDeg * OfflineRegionModel.METERS_PER_LON_DEGREE_EQUATOR * kotlin.math.cos(Math.toRadians(12.9716))

        assertEquals("Physical North-South span must equal 2 * R (2000m)", 2000.0, bPhysicalLatMeters, 0.5)
        assertEquals("Physical East-West span must equal 2 * R (2000m)", 2000.0, bPhysicalLonMeters, 0.5)
        assertEquals("Bangalore physical aspect ratio must be 1.0 (equilateral square)", 1.0, bPhysicalLonMeters / bPhysicalLatMeters, 0.001)

        // 2. High-Latitude (Sub-Arctic: 60.0°N, 10.0°E)
        // At 60°N, cos(60°) = 0.5, so longitude degrees must be 2.0x wider to maintain 2000m on ground
        val arcticRegion = mapManager.createGpsAreaRegion(
            latitude = 60.0,
            longitude = 10.0,
            radiusMeters = radiusMeters
        )

        val aLatSpanDeg = arcticRegion.maxLat - arcticRegion.minLat
        val aLonSpanDeg = arcticRegion.maxLon - arcticRegion.minLon
        val aPhysicalLatMeters = aLatSpanDeg * OfflineRegionModel.METERS_PER_LAT_DEGREE
        val aPhysicalLonMeters = aLonSpanDeg * OfflineRegionModel.METERS_PER_LON_DEGREE_EQUATOR * kotlin.math.cos(Math.toRadians(60.0))

        assertEquals("Arctic physical N-S span must equal 2000m", 2000.0, aPhysicalLatMeters, 0.5)
        assertEquals("Arctic physical E-W span must equal 2000m", 2000.0, aPhysicalLonMeters, 0.5)
        assertEquals("At 60°N, longitude degree span must be ~2.0x latitude degree span", 2.0, aLonSpanDeg / aLatSpanDeg, 0.01)

        // 3. Equator (0.0°N, 0.0°E)
        val equatorRegion = mapManager.createGpsAreaRegion(
            latitude = 0.0,
            longitude = 0.0,
            radiusMeters = radiusMeters
        )
        val eqLatSpanDeg = equatorRegion.maxLat - equatorRegion.minLat
        val eqLonSpanDeg = equatorRegion.maxLon - equatorRegion.minLon
        assertEquals("At equator, degree spans are nearly 1:1", 1.0, eqLonSpanDeg / eqLatSpanDeg, 0.01)
    }

    @Test
    fun testCreateGpsAreaRegion_allSelectableRadiusOptions() {
        val testRadii = listOf(10f, 500f, 1000f, 3000f)

        testRadii.forEach { radius ->
            val region = mapManager.createGpsAreaRegion(
                latitude = 12.9716,
                longitude = 77.5946,
                radiusMeters = radius
            )

            assertNotNull(region)
            assertTrue("Region must be registered in catalog", mapManager.getAllRegions().any { it.regionId == region.regionId })
            assertEquals(OfflineRegionStatus.AVAILABLE, region.status)
            assertTrue(region.contains(12.9716, 77.5946))
            assertTrue(region.fileSizeBytes > 0L)
        }
    }

    @Test
    fun testEmptyStorageFallback_zeroDownloadedRegionsOnCleanInstall() {
        // Given a fresh installation with empty disk:
        val downloaded = mapManager.getDownloadedRegions()
        assertTrue("Downloaded regions must be strictly empty when disk has 0 mbtiles", downloaded.isEmpty())
        assertEquals("Total storage used must be exactly 0 bytes", 0L, mapManager.getTotalStorageUsedBytes())
        assertEquals(0.0f, mapManager.getQuotaUsagePercentage(), 0.001f)

        // Radar/Situational canvas fallback must be active everywhere
        assertTrue(mapManager.shouldFallbackToCanvas(12.9716, 77.5946))
        assertTrue(mapManager.shouldFallbackToCanvas(28.6139, 77.2090))
        assertFalse(mapManager.hasOfflineMapFor(12.9716, 77.5946))
    }

    @Test
    fun testPurgeFakeDemoRegions_noFakeSectorAlphaMarkedDownloaded() {
        val allRegions = mapManager.getAllRegions()

        // Sector Alpha was fake demonstration data in older prototype builds
        val fakeSectorAlpha = allRegions.find { it.regionId == "region-alpha-metro" || it.name.contains("Sector Alpha") }
        assertNull("Fake 'Sector Alpha' must not exist in genuine offline map catalog", fakeSectorAlpha)

        // All catalog regions must strictly start in AVAILABLE status, NEVER falsely DOWNLOADED
        allRegions.forEach { region ->
            assertFalse(
                "Region ${region.name} cannot be marked DOWNLOADED when file is absent from disk",
                region.status == OfflineRegionStatus.DOWNLOADED
            )
        }
    }
}
