package com.rescuemesh.ai.location

/**
 * Encapsulates a genuine geographic location measurement acquired from physical device hardware.
 *
 * @param latitude Geographic latitude in decimal degrees.
 * @param longitude Geographic longitude in decimal degrees.
 * @param accuracy Horizontal accuracy radius in meters, or null if unspecified.
 * @param provider Underlying location source name (e.g. "fused", "gps", "network", "fake").
 * @param timestamp Epoch millisecond timestamp when the location fix was generated.
 */
data class LocationResult(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val provider: String,
    val timestamp: Long = System.currentTimeMillis()
)

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
}
