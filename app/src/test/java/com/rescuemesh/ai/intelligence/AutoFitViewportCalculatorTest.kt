package com.rescuemesh.ai.intelligence

import com.rescuemesh.ai.ui.screens.SituationalPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AutoFitViewportCalculatorTest {

    private val delta = 1e-4

    @Test
    fun emptyPointsListReturnsFallbackCenterAndDefaultRadius() {
        val bounds = AutoFitViewportCalculator.computeViewport(
            points = emptyList(),
            fallbackCenterLat = 12.9716,
            fallbackCenterLon = 77.5946
        )

        assertEquals(12.9716, bounds.centerLat, delta)
        assertEquals(77.5946, bounds.centerLon, delta)
        assertEquals(500f, bounds.radiusMeters, 0.1f)
        assertFalse(bounds.crossesAntimeridian)
        assertEquals(0.0, bounds.spanLat, delta)
        assertEquals(0.0, bounds.spanLon, delta)
        assertTrue(bounds.dynamicRingsMeters.isNotEmpty())
    }

    @Test
    fun singlePointReturnsCenteredCoordinateAndMinimumRadiusFloor() {
        val point = GeoPoint(13.0, 77.0)
        val bounds = AutoFitViewportCalculator.computeViewport(listOf(point))

        assertEquals(13.0, bounds.centerLat, delta)
        assertEquals(77.0, bounds.centerLon, delta)
        // Single point should have minimum radius floor (100m)
        assertEquals(AutoFitViewportCalculator.MIN_RADIUS_METERS, bounds.radiusMeters, 0.1f)
        assertFalse(bounds.crossesAntimeridian)
        assertEquals(0.0, bounds.spanLat, delta)
        assertEquals(0.0, bounds.spanLon, delta)
    }

    @Test
    fun identicalMultiplePointsReturnCenteredCoordinateAndMinimumRadiusFloor() {
        val points = listOf(
            GeoPoint(13.0, 77.0),
            GeoPoint(13.0, 77.0),
            GeoPoint(13.0, 77.0)
        )
        val bounds = AutoFitViewportCalculator.computeViewport(points)

        assertEquals(13.0, bounds.centerLat, delta)
        assertEquals(77.0, bounds.centerLon, delta)
        assertEquals(AutoFitViewportCalculator.MIN_RADIUS_METERS, bounds.radiusMeters, 0.1f)
    }

    @Test
    fun twoPointsCalculateCorrectMetricSpanAndVisualPadding() {
        // Points spaced 0.01 deg latitude apart at equator
        // 0.01 deg lat ≈ 1111.33 meters
        val p1 = GeoPoint(0.0, 0.0)
        val p2 = GeoPoint(0.01, 0.0)

        val padding = 0.20f // 20% visual padding
        val bounds = AutoFitViewportCalculator.computeViewport(
            points = listOf(p1, p2),
            paddingPercent = padding
        )

        assertEquals(0.005, bounds.centerLat, delta)
        assertEquals(0.0, bounds.centerLon, delta)
        assertEquals(0.01, bounds.spanLat, delta)

        val expectedSpanY = 0.01 * AutoFitViewportCalculator.METERS_PER_DEGREE_LAT
        assertEquals(expectedSpanY, bounds.spanYMeters, 1.0)

        // Padded span is span * (1 + 2 * padding) = span * 1.4
        val expectedPaddedSpanY = expectedSpanY * 1.4
        val expectedRadius = (expectedPaddedSpanY / 2.0).toFloat()
        assertEquals(expectedRadius, bounds.radiusMeters, 2.0f)
    }

    @Test
    fun antimeridianCrossingPointsCalculateShortestSpanInsteadOfGlobeSpanning() {
        // Point A at +179.9°, Point B at -179.9° (span is 0.2°, NOT 359.8°)
        val p1 = GeoPoint(0.0, 179.9)
        val p2 = GeoPoint(0.0, -179.9)

        val bounds = AutoFitViewportCalculator.computeViewport(listOf(p1, p2))

        assertTrue(bounds.crossesAntimeridian)
        assertEquals(0.2, bounds.spanLon, delta)

        // Center should be on the antimeridian (±180°)
        val absCenter = abs(bounds.centerLon)
        assertTrue("Center lon should be near 180°", abs(absCenter - 180.0) < delta)

        // Metric span should be 0.2 * 111320m ≈ 22,264m
        val expectedSpanX = 0.2 * AutoFitViewportCalculator.METERS_PER_DEGREE_LON_EQUATOR
        assertEquals(expectedSpanX, bounds.spanXMeters, 50.0)
    }

    @Test
    fun multiplePointsSpanningAntimeridianCalculateCorrectSpan() {
        // Points in Fiji / New Zealand area: +178.0, +179.5, -179.5, -178.0
        // Expected span is 4.0 degrees across the dateline
        val points = listOf(
            GeoPoint(-18.0, 178.0),
            GeoPoint(-18.0, 179.5),
            GeoPoint(-18.0, -179.5),
            GeoPoint(-18.0, -178.0)
        )

        val bounds = AutoFitViewportCalculator.computeViewport(points)

        assertTrue(bounds.crossesAntimeridian)
        assertEquals(4.0, bounds.spanLon, delta)
        val absCenter = abs(bounds.centerLon)
        assertTrue("Center should be at the antimeridian", abs(absCenter - 180.0) < delta)
    }

    @Test
    fun nonCrossingHemispherePointsDoNotTriggerAntimeridianFlag() {
        val points = listOf(
            GeoPoint(10.0, 10.0),
            GeoPoint(10.0, 20.0),
            GeoPoint(12.0, 15.0)
        )
        val bounds = AutoFitViewportCalculator.computeViewport(points)

        assertFalse(bounds.crossesAntimeridian)
        assertEquals(10.0, bounds.spanLon, delta)
        assertEquals(15.0, bounds.centerLon, delta)
    }

    @Test
    fun extremeLatitudeSphericalScalingAppliesCosineCorrection() {
        // 1 deg longitude at Equator (lat 0°) vs Sub-arctic (lat 60°)
        val equatorP1 = GeoPoint(0.0, 10.0)
        val equatorP2 = GeoPoint(0.0, 11.0)
        val equatorBounds = AutoFitViewportCalculator.computeViewport(listOf(equatorP1, equatorP2))

        val arcticP1 = GeoPoint(60.0, 10.0)
        val arcticP2 = GeoPoint(60.0, 11.0)
        val arcticBounds = AutoFitViewportCalculator.computeViewport(listOf(arcticP1, arcticP2))

        // cos(60°) = 0.5, so spanXMeters at 60° should be roughly half of that at 0°
        assertEquals(
            equatorBounds.spanXMeters * 0.5,
            arcticBounds.spanXMeters,
            equatorBounds.spanXMeters * 0.05
        )
    }

    @Test
    fun minimumRadiusFloorOf100mIsEnforcedForMicroSpans() {
        // Two points separated by only 5 meters (~0.000045 degrees lat)
        val p1 = GeoPoint(12.9716, 77.5946)
        val p2 = GeoPoint(12.971645, 77.5946)

        val bounds = AutoFitViewportCalculator.computeViewport(listOf(p1, p2))

        assertEquals(AutoFitViewportCalculator.MIN_RADIUS_METERS, bounds.radiusMeters, 0.01f)
    }

    @Test
    fun maximumRadiusCeilingOf50kmIsEnforcedForRegionalSpans() {
        // Two points separated by ~200 km (~2 degrees lat)
        val p1 = GeoPoint(10.0, 77.0)
        val p2 = GeoPoint(12.0, 77.0)

        val bounds = AutoFitViewportCalculator.computeViewport(listOf(p1, p2))

        assertEquals(AutoFitViewportCalculator.MAX_RADIUS_METERS, bounds.radiusMeters, 0.01f)
    }

    @Test
    fun canvasAspectPreservationAdaptsRadiusForLandscapeAndPortrait() {
        val points = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.01, 0.02)
        )

        // Portrait canvas: width = 400, height = 800
        val portraitBounds = AutoFitViewportCalculator.computeViewportForCanvas(
            points = points,
            canvasWidthPx = 400f,
            canvasHeightPx = 800f
        )

        // Landscape canvas: width = 800, height = 400
        val landscapeBounds = AutoFitViewportCalculator.computeViewportForCanvas(
            points = points,
            canvasWidthPx = 800f,
            canvasHeightPx = 400f
        )

        assertTrue(portraitBounds.radiusMeters >= AutoFitViewportCalculator.MIN_RADIUS_METERS)
        assertTrue(landscapeBounds.radiusMeters >= AutoFitViewportCalculator.MIN_RADIUS_METERS)
    }

    @Test
    fun dynamicMetricDistanceRingsScaleWithReadableIntervals() {
        val smallRings = AutoFitViewportCalculator.calculateDynamicRings(120f)
        assertTrue("Rings should not exceed radius", smallRings.all { it <= 120 })
        assertTrue(smallRings.contains(50) || smallRings.contains(100))

        val mediumRings = AutoFitViewportCalculator.calculateDynamicRings(600f)
        assertTrue("Rings should not exceed radius", mediumRings.all { it <= 600 })
        assertTrue(mediumRings.contains(100) && mediumRings.contains(250) && mediumRings.contains(500))

        val largeRings = AutoFitViewportCalculator.calculateDynamicRings(4500f)
        assertTrue("Rings should not exceed radius", largeRings.all { it <= 4500 })
        assertTrue(largeRings.contains(1000) && largeRings.contains(2500))

        val regionalRings = AutoFitViewportCalculator.calculateDynamicRings(30000f)
        assertTrue("Rings should not exceed radius", regionalRings.all { it <= 30000 })
        assertTrue(regionalRings.contains(10000) && regionalRings.contains(25000))
    }

    @Test
    fun formatDistanceProducesReadableTacticalLabels() {
        assertEquals("50m", AutoFitViewportCalculator.formatDistance(50))
        assertEquals("500m", AutoFitViewportCalculator.formatDistance(500))
        assertEquals("1 km", AutoFitViewportCalculator.formatDistance(1000))
        assertEquals("2.5 km", AutoFitViewportCalculator.formatDistance(2500))
        assertEquals("10 km", AutoFitViewportCalculator.formatDistance(10000))
    }

    @Test
    fun computeViewportFromSituationalPointsWorksCorrectly() {
        val sitPoints = listOf(
            SituationalPoint(id = "1", latitude = 12.0, longitude = 77.0, label = "P1", type = "SOS"),
            SituationalPoint(id = "2", latitude = 12.05, longitude = 77.05, label = "P2", type = "HAZARD")
        )

        val bounds = AutoFitViewportCalculator.computeViewportFromPoints(sitPoints)

        assertEquals(12.025, bounds.centerLat, delta)
        assertEquals(77.025, bounds.centerLon, delta)
        assertTrue(bounds.radiusMeters >= AutoFitViewportCalculator.MIN_RADIUS_METERS)
    }
}
