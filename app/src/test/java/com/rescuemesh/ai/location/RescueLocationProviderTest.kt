package com.rescuemesh.ai.location

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Validates [RescueLocationProvider] contract compliance, streaming Flow,
 * fix quality age tiers, and backwards compatibility.
 * Enforces Architectural Invariants:
 * - When GPS is unavailable, returns null (UNKNOWN), never fabricated coordinates.
 * - When GPS fix is available, preserves accuracy, provider, timestamp, bearing, speed, altitude.
 * - Fix quality classifies: LIVE (<15s), STALE (15-60s), UNKNOWN (>60s or null).
 * - observeLocationUpdates emits real-time location stream.
 */
class RescueLocationProviderTest {

    private lateinit var fakeLocationProvider: FakeLocationProvider

    @Before
    fun setUp() {
        fakeLocationProvider = FakeLocationProvider()
    }

    @Test
    fun `when location is unavailable, provider returns null rather than fabricated coordinates`() = runBlocking {
        fakeLocationProvider.setSimulateUnavailable()

        val lastKnown = fakeLocationProvider.getLastKnownLocation()
        val current = fakeLocationProvider.getCurrentLocation()

        assertNull("Subterranean/collapsed zone with no GPS must return null lastKnown", lastKnown)
        assertNull("Subterranean/collapsed zone with no GPS must return null current", current)
        assertEquals(1, fakeLocationProvider.lastKnownCalls)
        assertEquals(1, fakeLocationProvider.currentLocationCalls)
    }

    @Test
    fun `when location is available, provider preserves coordinates accuracy provider and timestamp`() = runBlocking {
        val testTimestamp = 1700000000000L
        val realLocation = LocationResult(
            latitude = 37.7749,
            longitude = -122.4194,
            accuracy = 4.2f,
            provider = "gps",
            timestamp = testTimestamp
        )

        fakeLocationProvider.setLocation(realLocation)

        val acquired = fakeLocationProvider.getCurrentLocation()
        assertNotNull(acquired)
        assertEquals(37.7749, acquired!!.latitude, 1e-6)
        assertEquals(-122.4194, acquired.longitude, 1e-6)
        assertEquals(4.2f, acquired.accuracy ?: 0f, 0.01f)
        assertEquals("gps", acquired.provider)
        assertEquals(testTimestamp, acquired.timestamp)
        assertNull(acquired.bearing)
        assertNull(acquired.speed)
        assertNull(acquired.altitude)
    }

    @Test
    fun `when hardware provider throws exception, caller handles gracefully`() = runBlocking {
        fakeLocationProvider.setSimulateError(true)

        try {
            fakeLocationProvider.getCurrentLocation()
            fail("Expected exception when simulated error is set")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Simulated") == true)
        }
    }

    @Test
    fun `locationResult preserves extended fields bearing speed altitude with backwards compatibility`() {
        // Backwards-compatible 5-argument constructor
        val legacy = LocationResult(
            latitude = 12.9716,
            longitude = 77.5946,
            accuracy = 5.0f,
            provider = "fused",
            timestamp = 1000L
        )
        assertNull(legacy.bearing)
        assertNull(legacy.speed)
        assertNull(legacy.altitude)

        // Extended constructor with full telemetry
        val extended = LocationResult(
            latitude = 12.9716,
            longitude = 77.5946,
            accuracy = 3.5f,
            provider = "gps",
            timestamp = 2000L,
            bearing = 142.5f,
            speed = 1.4f,
            altitude = 920.0
        )
        assertEquals(142.5f, extended.bearing ?: 0f, 0.01f)
        assertEquals(1.4f, extended.speed ?: 0f, 0.01f)
        assertEquals(920.0, extended.altitude ?: 0.0, 0.01)
    }

    @Test
    fun `fix quality correctly transitions from LIVE to STALE to UNKNOWN based on elapsed age`() {
        val now = 100_000L

        // LIVE: Fix age < 15 seconds (e.g. 5 seconds old)
        val liveFix = LocationResult(
            latitude = 10.0,
            longitude = 20.0,
            accuracy = 5.0f,
            provider = "gps",
            timestamp = now - 5_000L
        )
        assertEquals(LocationFixQuality.LIVE, liveFix.computeQuality(now))

        // Boundary LIVE: Fix age 14.999s old
        val boundaryLiveFix = LocationResult(
            latitude = 10.0,
            longitude = 20.0,
            accuracy = 5.0f,
            provider = "gps",
            timestamp = now - 14_999L
        )
        assertEquals(LocationFixQuality.LIVE, boundaryLiveFix.computeQuality(now))

        // STALE: Fix age 15 to 60 seconds (e.g. 30 seconds old)
        val staleFix = LocationResult(
            latitude = 10.0,
            longitude = 20.0,
            accuracy = 5.0f,
            provider = "gps",
            timestamp = now - 30_000L
        )
        assertEquals(LocationFixQuality.STALE, staleFix.computeQuality(now))

        // Boundary STALE: Fix age exactly 60.000s old
        val boundaryStaleFix = LocationResult(
            latitude = 10.0,
            longitude = 20.0,
            accuracy = 5.0f,
            provider = "gps",
            timestamp = now - 60_000L
        )
        assertEquals(LocationFixQuality.STALE, boundaryStaleFix.computeQuality(now))

        // UNKNOWN: Fix age > 60 seconds (e.g. 75 seconds old)
        val unknownFix = LocationResult(
            latitude = 10.0,
            longitude = 20.0,
            accuracy = 5.0f,
            provider = "gps",
            timestamp = now - 75_000L
        )
        assertEquals(LocationFixQuality.UNKNOWN, unknownFix.computeQuality(now))

        // UNKNOWN: Null location result
        val nullFix: LocationResult? = null
        assertEquals(LocationFixQuality.UNKNOWN, nullFix.computeQuality(now))

        // Clock skew tolerance: Timestamp slightly in the future is treated as LIVE
        val futureFix = LocationResult(
            latitude = 10.0,
            longitude = 20.0,
            accuracy = 5.0f,
            provider = "gps",
            timestamp = now + 2_000L
        )
        assertEquals(LocationFixQuality.LIVE, futureFix.computeQuality(now))
    }

    @Test
    fun `observeLocationUpdates emits live stream of consecutive location fixes`() = runBlocking {
        val fix1 = LocationResult(12.91, 77.61, 4.0f, "gps", 1000L, bearing = 45f)
        val fix2 = LocationResult(12.92, 77.62, 3.5f, "gps", 4000L, bearing = 90f)
        val fix3 = LocationResult(12.93, 77.63, 3.0f, "gps", 7000L, bearing = 135f)

        fakeLocationProvider.setLocation(fix1)

        val collectedFixes = mutableListOf<LocationResult>()
        val job = launch {
            fakeLocationProvider.observeLocationUpdates(intervalMs = 3000L, minDistanceMeters = 1.0f)
                .take(3)
                .toList(collectedFixes)
        }

        fakeLocationProvider.emitLocation(fix2)
        fakeLocationProvider.emitLocation(fix3)

        job.join()

        assertEquals(3, collectedFixes.size)
        assertEquals(12.91, collectedFixes[0].latitude, 1e-5)
        assertEquals(12.92, collectedFixes[1].latitude, 1e-5)
        assertEquals(12.93, collectedFixes[2].latitude, 1e-5)
        assertEquals(45f, collectedFixes[0].bearing ?: 0f, 0.01f)
        assertEquals(90f, collectedFixes[1].bearing ?: 0f, 0.01f)
        assertEquals(135f, collectedFixes[2].bearing ?: 0f, 0.01f)
        assertTrue(fakeLocationProvider.observeUpdatesCalls > 0)
    }
}
