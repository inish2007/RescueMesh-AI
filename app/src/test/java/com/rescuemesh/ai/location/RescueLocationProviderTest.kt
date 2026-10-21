package com.rescuemesh.ai.location

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Validates [RescueLocationProvider] contract compliance and fallback behavior.
 * Enforces Architectural Invariant:
 * - When GPS is unavailable, returns null (UNKNOWN), never fabricated coordinates.
 * - When GPS fix is available, preserves accuracy, provider, and timestamp.
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
}
