package com.rescuemesh.ai.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineRegionModelTest {

    @Test
    fun testRegionModel_creationAndBasicProperties() {
        val region = OfflineRegionModel(
            regionId = "test_sector",
            name = "Test Sector",
            description = "Test Sector Description",
            minLat = 12.0,
            maxLat = 13.0,
            minLon = 77.0,
            maxLon = 78.0,
            fileSizeBytes = 10_485_760L // 10 MB
        )

        assertEquals("test_sector", region.regionId)
        assertEquals("Test Sector", region.name)
        assertEquals("test_sector.mbtiles", region.fileName)
        assertEquals(OfflineRegionStatus.AVAILABLE, region.status)
        assertFalse(region.isDownloaded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testRegionModel_invalidLatitudeOrder_throwsException() {
        OfflineRegionModel(
            regionId = "invalid_lat",
            name = "Invalid Lat",
            minLat = 15.0,
            maxLat = 10.0, // min > max
            minLon = 77.0,
            maxLon = 78.0
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testRegionModel_outOfRangeLatitude_throwsException() {
        OfflineRegionModel(
            regionId = "out_of_range",
            name = "Out of Range",
            minLat = -95.0,
            maxLat = 10.0,
            minLon = 0.0,
            maxLon = 10.0
        )
    }

    @Test
    fun testRegionModel_containsCoordinate_withinBounds() {
        val region = OfflineRegionModel(
            regionId = "bangalore",
            name = "Bangalore",
            minLat = 12.80,
            maxLat = 13.20,
            minLon = 77.40,
            maxLon = 77.80
        )

        // Inside center
        assertTrue(region.contains(13.00, 77.60))

        // On boundaries
        assertTrue(region.contains(12.80, 77.40)) // SW corner
        assertTrue(region.contains(13.20, 77.80)) // NE corner

        // Outside bounds
        assertFalse(region.contains(12.79, 77.60)) // Too far South
        assertFalse(region.contains(13.21, 77.60)) // Too far North
        assertFalse(region.contains(13.00, 77.39)) // Too far West
        assertFalse(region.contains(13.00, 77.81)) // Too far East
    }

    @Test
    fun testRegionModel_containsCoordinate_antimeridianCrossing() {
        // Bounding box crossing the 180 meridian (e.g., Eastern Russia / Aleutian Islands / Fiji)
        val region = OfflineRegionModel(
            regionId = "pacific_sector",
            name = "Pacific Disaster Sector",
            minLat = -20.0,
            maxLat = -10.0,
            minLon = 175.0,
            maxLon = -175.0 // Spans antimeridian
        )

        // Points inside (Eastern hemisphere side)
        assertTrue(region.contains(-15.0, 178.0))
        assertTrue(region.contains(-15.0, 175.0))

        // Points inside (Western hemisphere side)
        assertTrue(region.contains(-15.0, -178.0))
        assertTrue(region.contains(-15.0, -175.0))

        // Points outside
        assertFalse(region.contains(-15.0, 0.0))
        assertFalse(region.contains(-15.0, 170.0))
        assertFalse(region.contains(-15.0, -170.0))
        assertFalse(region.contains(-25.0, 178.0)) // Outside latitude
    }

    @Test
    fun testRegionModel_formatSize() {
        val smallRegion = OfflineRegionModel(
            regionId = "small",
            name = "Small",
            minLat = 0.0, maxLat = 1.0, minLon = 0.0, maxLon = 1.0,
            fileSizeBytes = 52_428_800L // 50.0 MB
        )
        assertEquals("50.0 MB", smallRegion.formatSize())

        val largeRegion = OfflineRegionModel(
            regionId = "large",
            name = "Large",
            minLat = 0.0, maxLat = 1.0, minLon = 0.0, maxLon = 1.0,
            fileSizeBytes = 1_610_612_736L // 1.50 GB
        )
        assertEquals("1.50 GB", largeRegion.formatSize())
    }
}
