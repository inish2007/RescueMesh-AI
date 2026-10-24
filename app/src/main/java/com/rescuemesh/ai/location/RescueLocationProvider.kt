package com.rescuemesh.ai.location

import kotlinx.coroutines.flow.Flow

/**
 * Tactical fix quality tiers based on elapsed acquisition age:
 * - LIVE: Fix age < 15 seconds
 * - STALE: Fix age 15 to 60 seconds
 * - UNKNOWN: Fix age > 60 seconds or null
 */
enum class LocationFixQuality {
    LIVE,
    STALE,
    UNKNOWN
}

/**
 * Encapsulates a genuine geographic location measurement acquired from physical device hardware.
 *
 * @param latitude Geographic latitude in decimal degrees.
 * @param longitude Geographic longitude in decimal degrees.
 * @param accuracy Horizontal accuracy radius in meters, or null if unspecified.
 * @param provider Underlying location source name (e.g. "fused", "gps", "network", "fake").
 * @param timestamp Epoch millisecond timestamp when the location fix was generated.
 * @param bearing Degrees East of True North (0.0 .. 360.0), or null if unavailable.
 * @param speed Ground speed in meters per second, or null if unavailable.
 * @param altitude Altitude in meters above WGS84 ellipsoid, or null if unavailable.
 */
data class LocationResult(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val provider: String,
    val timestamp: Long = System.currentTimeMillis(),
    val bearing: Float? = null,
    val speed: Float? = null,
    val altitude: Double? = null
) {
    /**
     * Fix quality classification based on fix age relative to [now].
     */
    fun computeQuality(now: Long = System.currentTimeMillis()): LocationFixQuality {
        val ageMs = now - timestamp
        if (ageMs < 0) return LocationFixQuality.LIVE // Clock skew tolerance
        return when {
            ageMs < 15_000L -> LocationFixQuality.LIVE
            ageMs <= 60_000L -> LocationFixQuality.STALE
            else -> LocationFixQuality.UNKNOWN
        }
    }

    val quality: LocationFixQuality
        get() = computeQuality()
}

/**
 * Extension helper for nullable LocationResult quality classification.
 */
fun LocationResult?.computeQuality(now: Long = System.currentTimeMillis()): LocationFixQuality {
    return this?.computeQuality(now) ?: LocationFixQuality.UNKNOWN
}

/**
 * Contract for acquiring geographical coordinates in network-denied disaster environments.
 *
 * In subterranean, structural collapse, or signal-shielded disaster zones where GPS is
 * unavailable, implementations must return `null` rather than fabricating coordinates.
 */
interface RescueLocationProvider {
    /**
     * Retrieves the most recent cached location fix, or null if unavailable/disabled/denied.
     * Fast return, non-blocking.
     */
    suspend fun getLastKnownLocation(): LocationResult?

    /**
     * Actively requests a fresh location fix from hardware GPS / sensors.
     * Suspends until a fix is obtained, or times out and returns null.
     */
    suspend fun getCurrentLocation(): LocationResult?

    /**
     * Emits continuous location fixes while actively collected.
     * Cold Flow: GPS hardware is enabled ONLY when at least one collector is active.
     *
     * @param intervalMs Desired update interval in milliseconds (default 3000ms = 3s).
     * @param minDistanceMeters Minimum displacement in meters to trigger an update (default 1.0m).
     */
    fun observeLocationUpdates(
        intervalMs: Long = 3000L,
        minDistanceMeters: Float = 1.0f
    ): Flow<LocationResult>
}
