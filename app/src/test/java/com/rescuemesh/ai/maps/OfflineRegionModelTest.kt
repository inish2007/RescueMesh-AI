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

    @Test
    fun testCalculateBoundingBox_bangalore_accurateCosineScaling() {
        val box = OfflineRegionModel.calculateBoundingBox(
            latitude = 12.9716,
            longitude = 77.5946,
            radiusMeters = 1000f
        )

        // Delta Lat = 1000 / 111132.95 = ~0.008998 deg
        val deltaLat = box.maxLat - 12.9716
        assertEquals(1000.0 / 111132.95, deltaLat, 0.00001)

        // Delta Lon = 1000 / (111320.0 * cos(12.9716 deg))
        val cosLat = kotlin.math.cos(Math.toRadians(12.9716))
        val expectedDeltaLon = 1000.0 / (111320.0 * cosLat)
        val deltaLon = box.maxLon - 77.5946
        assertEquals(expectedDeltaLon, deltaLon, 0.00001)

        // Equilateral metric bounds on the ground
        val latSpanMeters = (box.maxLat - box.minLat) * OfflineRegionModel.METERS_PER_LAT_DEGREE
        val lonSpanMeters = (box.maxLon - box.minLon) * OfflineRegionModel.METERS_PER_LON_DEGREE_EQUATOR * cosLat
        assertEquals(2000.0, latSpanMeters, 0.1)
        assertEquals(2000.0, lonSpanMeters, 0.1)
    }

    @Test
    fun testCalculateBoundingBox_poleClamping_doesNotThrowOrOverflow() {
        // At North Pole (90 deg N)
        val northPoleBox = OfflineRegionModel.calculateBoundingBox(
            latitude = 90.0,
            longitude = 0.0,
            radiusMeters = 500f
        )

        // Latitude should be clamped to 90.0 max
        assertEquals(90.0, northPoleBox.maxLat, 0.00001)
        assertTrue(northPoleBox.minLat <= 90.0)

        // At South Pole (-90 deg S)
        val southPoleBox = OfflineRegionModel.calculateBoundingBox(
            latitude = -90.0,
            longitude = 0.0,
            radiusMeters = 500f
        )
        assertEquals(-90.0, southPoleBox.minLat, 0.00001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCalculateBoundingBox_invalidLatitude_throwsException() {
        OfflineRegionModel.calculateBoundingBox(
            latitude = 95.0, // Invalid latitude
            longitude = 0.0,
            radiusMeters = 1000f
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCalculateBoundingBox_nonPositiveRadius_throwsException() {
        OfflineRegionModel.calculateBoundingBox(
            latitude = 12.0,
            longitude = 77.0,
            radiusMeters = 0f // Non-positive radius
        )
    }
}
