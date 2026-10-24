package com.rescuemesh.ai.location

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Validates [ProximityGuidanceEngine] geodesics, cardinal sector mapping,
 * Astra P0-5 noise rejection, and mandatory safety invariants.
 */
class ProximityGuidanceEngineTest {

    private lateinit var engine: ProximityGuidanceEngine

    @Before
    fun setUp() {
        engine = ProximityGuidanceEngine(maxWindowSize = 5, maxWindowAgeMs = 30_000L)
    }

    @Test
    fun `haversineDistance accurately computes distance between known coordinates`() {
        // Equator 0,0 to 0, 0.001 (approx 111.19 meters)
        val shortDist = ProximityGuidanceEngine.haversineDistance(0.0, 0.0, 0.0, 0.001)
        assertEquals(111.19, shortDist, 0.5)

        // London to Paris (~343.5 km)
        val londonParis = ProximityGuidanceEngine.haversineDistance(51.5074, -0.1278, 48.8566, 2.3522)
        assertTrue(londonParis in 340_000.0..348_000.0)

        // Same point distance is 0.0
        val zeroDist = ProximityGuidanceEngine.haversineDistance(37.7749, -122.4194, 37.7749, -122.4194)
        assertEquals(0.0, zeroDist, 1e-6)
    }

    @Test
    fun `haversineDistance handles antimeridian crossing cleanly`() {
        // Point just west of antimeridian (179.9 deg) to point just east (-179.9 deg) at Equator
        // Separation is 0.2 degrees longitude (~22.2 km)
        val dist = ProximityGuidanceEngine.haversineDistance(0.0, 179.9, 0.0, -179.9)
        assertTrue("Distance across antimeridian must be ~22.2 km, was $dist", dist in 22_000.0..22_500.0)
    }

    @Test
    fun `initialBearing calculates accurate great-circle azimuth`() {
        // Due North from (0,0) to (1,0)
        val north = ProximityGuidanceEngine.initialBearing(0.0, 0.0, 1.0, 0.0)
        assertEquals(0.0, north, 0.1)

        // Due East from (0,0) to (0,1)
        val east = ProximityGuidanceEngine.initialBearing(0.0, 0.0, 0.0, 1.0)
        assertEquals(90.0, east, 0.1)

        // Due South from (1,0) to (0,0)
        val south = ProximityGuidanceEngine.initialBearing(1.0, 0.0, 0.0, 0.0)
        assertEquals(180.0, south, 0.1)

        // Due West from (0,1) to (0,0)
        val west = ProximityGuidanceEngine.initialBearing(0.0, 1.0, 0.0, 0.0)
        assertEquals(270.0, west, 0.1)
    }

    @Test
    fun `toCardinalSector correctly classifies all 8 sectors`() {
        assertEquals("N", ProximityGuidanceEngine.toCardinalSector(0.0))
        assertEquals("N", ProximityGuidanceEngine.toCardinalSector(15.0))
        assertEquals("N", ProximityGuidanceEngine.toCardinalSector(355.0))
        assertEquals("NE", ProximityGuidanceEngine.toCardinalSector(45.0))
        assertEquals("E", ProximityGuidanceEngine.toCardinalSector(90.0))
        assertEquals("SE", ProximityGuidanceEngine.toCardinalSector(135.0))
        assertEquals("S", ProximityGuidanceEngine.toCardinalSector(180.0))
        assertEquals("SW", ProximityGuidanceEngine.toCardinalSector(225.0))
        assertEquals("W", ProximityGuidanceEngine.toCardinalSector(270.0))
        assertEquals("NW", ProximityGuidanceEngine.toCardinalSector(315.0))
    }

    @Test
    fun `trend classifier identifies CLOSING_IN when user moves towards target beyond uncertainty`() {
        val targetLat = 12.9800
        val targetLon = 77.6000

        // User starts at 12.9700 and moves North towards target
        val fix1 = LocationResult(12.9700, 77.6000, accuracy = 4.0f, provider = "gps", timestamp = 1000L)
        val fix2 = LocationResult(12.9710, 77.6000, accuracy = 4.0f, provider = "gps", timestamp = 4000L)
        val fix3 = LocationResult(12.9720, 77.6000, accuracy = 4.0f, provider = "gps", timestamp = 7000L)

        val (trend, delta) = ProximityGuidanceEngine.classifyTrend(listOf(fix1, fix2, fix3), targetLat, targetLon)
        assertEquals(ProximityTrend.CLOSING_IN, trend)
        assertTrue("delta must be negative when closing in", delta < -10.0)
    }

    @Test
    fun `trend classifier identifies MOVING_AWAY when user moves away from target beyond uncertainty`() {
        val targetLat = 12.9800
        val targetLon = 77.6000

        // User starts at 12.9720 and moves South away from target
        val fix1 = LocationResult(12.9720, 77.6000, accuracy = 4.0f, provider = "gps", timestamp = 1000L)
        val fix2 = LocationResult(12.9710, 77.6000, accuracy = 4.0f, provider = "gps", timestamp = 4000L)
        val fix3 = LocationResult(12.9700, 77.6000, accuracy = 4.0f, provider = "gps", timestamp = 7000L)

        val (trend, delta) = ProximityGuidanceEngine.classifyTrend(listOf(fix1, fix2, fix3), targetLat, targetLon)
        assertEquals(ProximityTrend.MOVING_AWAY, trend)
        assertTrue("delta must be positive when moving away", delta > 10.0)
    }

    @Test
    fun `Astra P0-5 Enforcement rejects confident trend when displacement is smaller than GPS uncertainty`() {
        val targetLat = 12.9800
        val targetLon = 77.6000

        // Target is ~1.1 km away. Fixes have 15m GPS uncertainty.
        // Displacement between fixes is only ~2.2m (well below 15m sigma_GPS)
        val fix1 = LocationResult(12.97000, 77.60000, accuracy = 15.0f, provider = "gps", timestamp = 1000L)
        val fix2 = LocationResult(12.97002, 77.60000, accuracy = 15.0f, provider = "gps", timestamp = 4000L)

        val (trend, _) = ProximityGuidanceEngine.classifyTrend(listOf(fix1, fix2), targetLat, targetLon)
        assertEquals(
            "Displacement within GPS uncertainty must classify as STATIONARY_APPROXIMATE per Astra P0-5",
            ProximityTrend.STATIONARY_APPROXIMATE,
            trend
        )
    }

    @Test
    fun `when fixes are insufficient, classifies as STATIONARY_APPROXIMATE`() {
        val targetLat = 12.9800
        val targetLon = 77.6000
        val fix1 = LocationResult(12.9700, 77.6000, accuracy = 5.0f, provider = "gps", timestamp = 1000L)

        val (trend, delta) = ProximityGuidanceEngine.classifyTrend(listOf(fix1), targetLat, targetLon)
        assertEquals(ProximityTrend.STATIONARY_APPROXIMATE, trend)
        assertEquals(0.0, delta, 1e-6)

        val (emptyTrend, _) = ProximityGuidanceEngine.classifyTrend(emptyList(), targetLat, targetLon)
        assertEquals(ProximityTrend.STATIONARY_APPROXIMATE, emptyTrend)
    }

    @Test
    fun `proximityGuidance includes mandatory safety warning text without safe route claims`() {
        val targetLat = 12.9800
        val targetLon = 77.6000
        val fix1 = LocationResult(12.9700, 77.6000, accuracy = 5.0f, provider = "gps", timestamp = 1000L)

        val guidance = ProximityGuidanceEngine.computeGuidance(listOf(fix1), targetLat, targetLon)
        assertEquals(
            "Straight-line proximity only. No surveyed walking route available. Watch for unmapped hazards.",
            guidance.safetyWarning
        )
        assertFalse(guidance.safetyWarning.contains("safe", ignoreCase = true))
    }

    @Test
    fun `stateful engine prunes window based on max size and age`() {
        val baseTime = 100_000L
        val targetLat = 12.9800
        val targetLon = 77.6000

        // Add 6 fixes (max size is 5)
        for (i in 0..5) {
            engine.addFix(
                LocationResult(12.9700 + i * 0.001, 77.6000, 4.0f, "gps", timestamp = baseTime + i * 2000L),
                now = baseTime + i * 2000L
            )
        }
        assertEquals("Window size must not exceed maxWindowSize 5", 5, engine.windowSize)

        // Advance time beyond maxWindowAgeMs (30s)
        val futureTime = baseTime + 45_000L
        val freshFix = LocationResult(12.9760, 77.6000, 4.0f, "gps", timestamp = futureTime)
        val guidance = engine.getGuidance(targetLat, targetLon, currentFix = freshFix, now = futureTime)

        assertNotNull(guidance)
        // All previous fixes were older than 30s relative to futureTime, so only the fresh fix remains
        assertEquals(1, engine.windowSize)
    }
}
