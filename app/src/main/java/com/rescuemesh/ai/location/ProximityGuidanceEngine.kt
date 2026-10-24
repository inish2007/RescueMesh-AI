package com.rescuemesh.ai.location

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tactical proximity trend classification:
 * - CLOSING_IN: Distance to target is decreasing beyond GPS measurement uncertainty.
 * - MOVING_AWAY: Distance to target is increasing beyond GPS measurement uncertainty.
 * - STATIONARY_APPROXIMATE: Displacement between fixes is within GPS noise (delta_d < sigma_GPS),
 *   insufficient fixes are available, or displacement is not predominantly along the target radial.
 */
enum class ProximityTrend {
    CLOSING_IN,
    MOVING_AWAY,
    STATIONARY_APPROXIMATE
}

/**
 * Straight-line proximity guidance towards a selected SOS emergency target.
 *
 * Invariant: Never describes routes as "safe" and strictly avoids synthetic path creation.
 */
data class ProximityGuidance(
    val distanceMeters: Double,
    val bearingDegrees: Double,
    val cardinalBearing: String,
    val trend: ProximityTrend,
    val trendDeltaMeters: Double,
    val safetyWarning: String = SAFETY_WARNING_TEXT
) {
    companion object {
        const val SAFETY_WARNING_TEXT =
            "Straight-line proximity only. No surveyed walking route available. Watch for unmapped hazards."
    }
}

/**
 * Computes geodesic distance, initial great-circle bearing, 8-sector cardinal heading,
 * and noise-aware proximity trend classification (Astra P0-5 compliant).
 */
class ProximityGuidanceEngine(
    private val maxWindowSize: Int = DEFAULT_WINDOW_SIZE,
    private val maxWindowAgeMs: Long = DEFAULT_WINDOW_AGE_MS
) {
    companion object {
        const val DEFAULT_WINDOW_SIZE = 5
        const val DEFAULT_WINDOW_AGE_MS = 30_000L // 30 seconds
        const val DEFAULT_ACCURACY = 8.0f // Conservative fallback accuracy in meters
        const val MIN_DISPLACEMENT_THRESHOLD = 3.0 // Minimum 3m physical movement threshold
        const val SAFETY_WARNING_TEXT =
            "Straight-line proximity only. No surveyed walking route available. Watch for unmapped hazards."

        private const val EARTH_RADIUS_METERS = 6_371_000.0

        private val CARDINAL_SECTORS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

        /**
         * Calculates great-circle geodesic distance between two points using the Haversine formula.
         * Handles antimeridian wrapping cleanly.
         *
         * @return Distance in meters.
         */
        fun haversineDistance(
            lat1: Double,
            lon1: Double,
            lat2: Double,
            lon2: Double
        ): Double {
            val phi1 = Math.toRadians(lat1)
            val phi2 = Math.toRadians(lat2)
            val dLat = Math.toRadians(lat2 - lat1)

            var diffLonDeg = (lon2 - lon1) % 360.0
            if (diffLonDeg > 180.0) diffLonDeg -= 360.0
            if (diffLonDeg < -180.0) diffLonDeg += 360.0
            val dLon = Math.toRadians(diffLonDeg)

            val a = sin(dLat / 2.0).pow(2) + cos(phi1) * cos(phi2) * sin(dLon / 2.0).pow(2)
            val clampedA = a.coerceIn(0.0, 1.0)
            val c = 2.0 * atan2(sqrt(clampedA), sqrt(1.0 - clampedA))

            return EARTH_RADIUS_METERS * c
        }

        /**
         * Calculates the initial great-circle bearing from point 1 to point 2.
         *
         * @return Bearing in degrees clockwise from True North [0.0, 360.0).
         */
        fun initialBearing(
            lat1: Double,
            lon1: Double,
            lat2: Double,
            lon2: Double
        ): Double {
            val phi1 = Math.toRadians(lat1)
            val phi2 = Math.toRadians(lat2)

            var diffLonDeg = (lon2 - lon1) % 360.0
            if (diffLonDeg > 180.0) diffLonDeg -= 360.0
            if (diffLonDeg < -180.0) diffLonDeg += 360.0
            val dLon = Math.toRadians(diffLonDeg)

            val y = sin(dLon) * cos(phi2)
            val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)

            val bearingRad = atan2(y, x)
            val bearingDeg = Math.toDegrees(bearingRad)
            return ((bearingDeg % 360.0) + 360.0) % 360.0
        }

        /**
         * Maps an azimuth bearing in degrees to one of 8 cardinal sectors:
         * N, NE, E, SE, S, SW, W, NW.
         */
        fun toCardinalSector(bearingDegrees: Double): String {
            val normalized = ((bearingDegrees % 360.0) + 360.0) % 360.0
            val sectorIndex = (((normalized + 22.5) / 45.0).toInt()) % 8
            return CARDINAL_SECTORS[sectorIndex]
        }

        /**
         * Astra P0-5 Compliant Trend Classifier:
         * Rejects confident movement trends (CLOSING_IN / MOVING_AWAY) whenever the displacement
         * between fixes is smaller than the GPS measurement uncertainty (delta_d < sigma_GPS);
         * in that case, returns STATIONARY_APPROXIMATE.
         *
         * @param fixes Chronologically ordered list of recent location fixes.
         * @param targetLat Latitude of target emergency SOS coordinate.
         * @param targetLon Longitude of target emergency SOS coordinate.
         */
        fun classifyTrend(
            fixes: List<LocationResult>,
            targetLat: Double,
            targetLon: Double
        ): Pair<ProximityTrend, Double> {
            if (fixes.size < 2) {
                return Pair(ProximityTrend.STATIONARY_APPROXIMATE, 0.0)
            }

            val oldest = fixes.first()
            val newest = fixes.last()

            // 1. Compute user displacement between earliest and latest fix
            val displacementMeters = haversineDistance(
                oldest.latitude, oldest.longitude,
                newest.latitude, newest.longitude
            )

            // 2. Compute GPS measurement uncertainty threshold (sigma_GPS)
            val oldestAcc = oldest.accuracy ?: DEFAULT_ACCURACY
            val newestAcc = newest.accuracy ?: DEFAULT_ACCURACY
            val sigmaGps = maxOf(oldestAcc, newestAcc, MIN_DISPLACEMENT_THRESHOLD.toFloat()).toDouble()

            // 3. Compute distance to target from oldest and newest fixes
            val distOldest = haversineDistance(oldest.latitude, oldest.longitude, targetLat, targetLon)
            val distNewest = haversineDistance(newest.latitude, newest.longitude, targetLat, targetLon)
            val trendDelta = distNewest - distOldest

            // Astra P0-5 Enforcement: Reject confident movement if displacement is within GPS noise
            if (displacementMeters < sigmaGps) {
                return Pair(ProximityTrend.STATIONARY_APPROXIMATE, trendDelta)
            }

            // Also ensure the change in distance to target is significant compared to uncertainty
            return when {
                trendDelta <= -sigmaGps -> Pair(ProximityTrend.CLOSING_IN, trendDelta)
                trendDelta >= sigmaGps -> Pair(ProximityTrend.MOVING_AWAY, trendDelta)
                else -> Pair(ProximityTrend.STATIONARY_APPROXIMATE, trendDelta)
            }
        }

        /**
         * Pure functional calculation of proximity guidance from a list of fixes and target coordinate.
         */
        fun computeGuidance(
            fixes: List<LocationResult>,
            targetLat: Double,
            targetLon: Double
        ): ProximityGuidance {
            val currentFix = fixes.lastOrNull()
                ?: throw IllegalArgumentException("At least one location fix is required to compute guidance")

            val distance = haversineDistance(currentFix.latitude, currentFix.longitude, targetLat, targetLon)
            val bearing = initialBearing(currentFix.latitude, currentFix.longitude, targetLat, targetLon)
            val cardinal = toCardinalSector(bearing)
            val (trend, delta) = classifyTrend(fixes, targetLat, targetLon)

            return ProximityGuidance(
                distanceMeters = distance,
                bearingDegrees = bearing,
                cardinalBearing = cardinal,
                trend = trend,
                trendDeltaMeters = delta,
                safetyWarning = SAFETY_WARNING_TEXT
            )
        }
    }

    private val window = ArrayDeque<LocationResult>()

    /**
     * Appends a new location fix to the rolling window and purges expired fixes.
     */
    @Synchronized
    fun addFix(fix: LocationResult, now: Long = System.currentTimeMillis()) {
        window.addLast(fix)
        pruneWindow(now)
    }

    /**
     * Computes proximity guidance towards the specified target coordinates using the
     * current rolling window. If [currentFix] is supplied, it is added to the window first.
     */
    @Synchronized
    fun getGuidance(
        targetLat: Double,
        targetLon: Double,
        currentFix: LocationResult? = null,
        now: Long = System.currentTimeMillis()
    ): ProximityGuidance? {
        if (currentFix != null) {
            addFix(currentFix, now)
        } else {
            pruneWindow(now)
        }

        if (window.isEmpty()) return null

        val fixList = window.toList()
        return computeGuidance(fixList, targetLat, targetLon)
    }

    /**
     * Clears all buffered fixes from the rolling window.
     */
    @Synchronized
    fun clear() {
        window.clear()
    }

    val windowSize: Int
        @Synchronized get() = window.size

    @Synchronized
    fun getRecentFixes(): List<LocationResult> = window.toList()

    private fun pruneWindow(now: Long) {
        val cutoff = now - maxWindowAgeMs
        while (window.isNotEmpty() && window.first().timestamp < cutoff) {
            window.removeFirst()
        }
        while (window.size > maxWindowSize) {
            window.removeFirst()
        }
    }
}
