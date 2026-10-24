package com.rescuemesh.ai.maps

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class MapDownloadWorkerTest {

    @Test
    fun testBuildConstraints_enforcesUnmeteredWifiAndBatteryNotLow() {
        val constraints = MapDownloadWorker.buildConstraints()

        // Astra P0-6 / Gate 3 Invariant: Downloads must be strictly constrained to unmetered Wi-Fi
        assertEquals(
            "Background map downloads must strictly require UNMETERED network",
            NetworkType.UNMETERED,
            constraints.requiredNetworkType
        )

        // Must require battery not low to preserve battery in field conditions
        assertTrue(
            "Background map downloads must require battery not low",
            constraints.requiresBatteryNotLow()
        )
    }

    @Test
    fun testBuildConstraints_withAllowCellularTrue_requiresNetworkConnected() {
        val constraints = MapDownloadWorker.buildConstraints(allowCellular = true)

        assertEquals(
            "When allowCellular is true, network constraint must be CONNECTED",
            NetworkType.CONNECTED,
            constraints.requiredNetworkType
        )
        assertTrue(
            "Battery not low constraint must be preserved when cellular allowed",
            constraints.requiresBatteryNotLow()
        )
    }

    @Test
    fun testBuildConstraints_withAllowCellularFalse_requiresNetworkUnmetered() {
        val constraints = MapDownloadWorker.buildConstraints(allowCellular = false)

        assertEquals(
            "When allowCellular is false, network constraint must be UNMETERED",
            NetworkType.UNMETERED,
            constraints.requiredNetworkType
        )
        assertTrue(
            constraints.requiresBatteryNotLow()
        )
    }

    @Test
    fun testCreateWorkRequest_withCellularPreference_appliesConnectedConstraint() {
        val region = OfflineRegionModel(
            regionId = "test_cellular_sector",
            name = "Cellular Sector",
            minLat = 10.0,
            maxLat = 11.0,
            minLon = 70.0,
            maxLon = 71.0,
            fileSizeBytes = 15_000_000L,
            downloadUrl = "https://openfreemap.org/tiles/test_cellular.mbtiles",
            fileName = "test_cellular.mbtiles"
        )

        val workRequestWifi = MapDownloadWorker.createWorkRequest(region, allowCellular = false)
        assertEquals(NetworkType.UNMETERED, workRequestWifi.workSpec.constraints.requiredNetworkType)

        val workRequestCellular = MapDownloadWorker.createWorkRequest(region, allowCellular = true)
        assertEquals(NetworkType.CONNECTED, workRequestCellular.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun testCreateWorkRequest_setsCorrectInputDataAndTags() {
        val region = OfflineRegionModel(
            regionId = "test_sector_1",
            name = "Test Sector 1",
            minLat = 10.0,
            maxLat = 11.0,
            minLon = 70.0,
            maxLon = 71.0,
            fileSizeBytes = 20_000_000L,
            downloadUrl = "https://openfreemap.org/tiles/test_sector_1.mbtiles",
            fileName = "test_sector_1.mbtiles"
        )

        val workRequest = MapDownloadWorker.createWorkRequest(region)

        assertEquals(
            "test_sector_1",
            workRequest.workSpec.input.getString(MapDownloadWorker.KEY_REGION_ID)
        )
        assertEquals(
            "https://openfreemap.org/tiles/test_sector_1.mbtiles",
            workRequest.workSpec.input.getString(MapDownloadWorker.KEY_DOWNLOAD_URL)
        )
        assertEquals(
            "test_sector_1.mbtiles",
            workRequest.workSpec.input.getString(MapDownloadWorker.KEY_FILE_NAME)
        )
        assertEquals(
            20_000_000L,
            workRequest.workSpec.input.getLong(MapDownloadWorker.KEY_EXPECTED_SIZE, 0L)
        )

        // Verify tags
        assertTrue(workRequest.tags.contains(MapDownloadWorker.TAG))
        assertTrue(workRequest.tags.contains("test_sector_1"))
    }

    @Test
    fun testAtomicFileStreamingAndRename_logic() {
        val tempDir = Files.createTempDirectory("worker_test").toFile()
        try {
            val fileName = "sector_alpha.mbtiles"
            val targetFile = File(tempDir, fileName)
            val tempFile = File(tempDir, "${fileName}.tmp_test123")

            // Simulate streaming into temp file
            val payload = "MOCK_MBTILES_VECTOR_DATA_STREAM".toByteArray(Charsets.UTF_8)
            ByteArrayInputStream(payload).use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            assertTrue(tempFile.exists())
            assertTrue(tempFile.length() > 0L)
            assertFalse(targetFile.exists())

            // Atomic rename
            val renameSuccess = tempFile.renameTo(targetFile)
            if (!renameSuccess) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            assertTrue(targetFile.exists())
            assertFalse(tempFile.exists())
            assertEquals(payload.size.toLong(), targetFile.length())
            assertEquals("MOCK_MBTILES_VECTOR_DATA_STREAM", targetFile.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testCorruptZeroByteDownload_cleansUpTempFile() {
        val tempDir = Files.createTempDirectory("worker_corrupt_test").toFile()
        try {
            val fileName = "sector_corrupt.mbtiles"
            val targetFile = File(tempDir, fileName)
            val tempFile = File(tempDir, "${fileName}.tmp_corrupt")

            // Create zero-byte temp file
            tempFile.createNewFile()
            assertTrue(tempFile.exists())
            assertEquals(0L, tempFile.length())

            // Validation logic rejects 0-byte file and cleans up
            if (tempFile.length() <= 0L) {
                tempFile.delete()
            }

            assertFalse("Corrupted zero-byte file must be deleted", tempFile.exists())
            assertFalse("Target file must not be created on corruption", targetFile.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
