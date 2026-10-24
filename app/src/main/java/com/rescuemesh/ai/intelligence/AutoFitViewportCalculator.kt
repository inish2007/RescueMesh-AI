package com.rescuemesh.ai.intelligence

import androidx.compose.ui.geometry.Offset
import com.rescuemesh.ai.ui.screens.SituationalPoint
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Tactical geographic coordinate pair.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double
) {
    companion object {
        fun fromSituationalPoint(point: SituationalPoint): GeoPoint =
            GeoPoint(point.latitude, point.longitude)
    }
}

/**
 * Calculated situational map viewport parameters with antimeridian support,
 * visual clearance padding, metric spans, and dynamic distance rings.
 */
data class ViewportBounds(
    val centerLat: Double,
    val centerLon: Double,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val spanLat: Double,
    val spanLon: Double,
    val spanXMeters: Double,
    val spanYMeters: Double,
    val radiusMeters: Float,
    val dynamicRingsMeters: List<Int>,
    val ringLabels: List<String>,
    val crossesAntimeridian: Boolean
)

/**
 * Intelligent Auto-Fit Situational Map Viewport Calculator.
 *
 * Implements Gate 3 Requirement R2:
 * 1. Bounding box computation across active incident and responder GPS coordinates.
 * 2. Antimeridian (±180°) normalization using circular gap sorting (largest gap complement)
 *    to handle date line crossings cleanly without spanning 359 degrees.
 * 3. 15%–25% visual clearance padding around the active bounding box.
 * 4. Geometric aspect ratio preservation fitting portrait and landscape canvas dimensions without clipping.
 * 5. Dynamic metric distance ring scale calculation (readable intervals: 50m, 100m, 250m, 500m, 1km, 2.5km, 5km, etc.).
 * 6. Hard safety bounds: minimum radius floor of 100m (avoids over-zooming on a single point),
 *    tactical ceiling of 50km.
 * 7. Zero synthetic routing: strictly stateless coordinate projection with complete graph isolation.
 */
object AutoFitViewportCalculator {

    const val METERS_PER_DEGREE_LAT = 111132.95
    const val METERS_PER_DEGREE_LON_EQUATOR = 111320.0

    const val MIN_RADIUS_METERS = 100f
    const val MAX_RADIUS_METERS = 50000f // 50 km tactical limit
    const val DEFAULT_PADDING_PERCENT = 0.20f // 20% visual clearance
    const val DEFAULT_RADIUS_METERS = 500f

    /**
     * Mandatory tactical safety disclaimer required by Sentinel [P0-4 / G3-R04].
     */
    const val SAFETY_DISCLAIMER =
        "Straight-line proximity only. No surveyed walking route available. Watch for unmapped hazards."

    const val EPSILON = 1e-7

    /**
     * Normalizes a longitude coordinate into the canonical half-open interval [-180.0, 180.0).
     */
    fun normalizeLongitude(longitude: Double): Double {
        var lon = (longitude + 180.0) % 360.0
        if (lon < 0.0) lon += 360.0
        return lon - 180.0
    }

    /**
     * Computes optimal viewport bounds for a set of [GeoPoint] coordinates.
     *
     * @param points List of geographic points (user location, observations, target, peer beacons).
     * @param paddingPercent Visual clearance padding percentage (e.g. 0.20 for 20% padding).
     * @param minRadiusMeters Minimum radius floor in meters (default 100m).
     * @param maxRadiusMeters Maximum tactical radius ceiling in meters (default 50km).
     * @param fallbackCenterLat Fallback center latitude when points list is empty.
     * @param fallbackCenterLon Fallback center longitude when points list is empty.
     * @return [ViewportBounds] containing center, bounding coordinates, metric radius, and rings.
     */
    fun computeViewport(
        points: List<GeoPoint>,
        paddingPercent: Float = DEFAULT_PADDING_PERCENT,
        minRadiusMeters: Float = MIN_RADIUS_METERS,
        maxRadiusMeters: Float = MAX_RADIUS_METERS,
        fallbackCenterLat: Double = 0.0,
        fallbackCenterLon: Double = 0.0
    ): ViewportBounds {
        if (points.isEmpty()) {
            val rings = calculateDynamicRings(DEFAULT_RADIUS_METERS)
            return ViewportBounds(
                centerLat = fallbackCenterLat,
                centerLon = normalizeLongitude(fallbackCenterLon),
                minLat = fallbackCenterLat,
                maxLat = fallbackCenterLat,
                minLon = normalizeLongitude(fallbackCenterLon),
                maxLon = normalizeLongitude(fallbackCenterLon),
                spanLat = 0.0,
                spanLon = 0.0,
                spanXMeters = 0.0,
                spanYMeters = 0.0,
                radiusMeters = DEFAULT_RADIUS_METERS.coerceIn(minRadiusMeters, maxRadiusMeters),
                dynamicRingsMeters = rings,
                ringLabels = formatRingLabels(rings),
                crossesAntimeridian = false
            )
        }

        val clampedPadding = paddingPercent.coerceIn(0.0f, 1.0f)
        val safeMinRadius = max(10f, minRadiusMeters)
        val safeMaxRadius = max(safeMinRadius, maxRadiusMeters)

        // Latitude bounding
        val lats = points.map { it.latitude }
        val minLat = lats.minOrNull() ?: fallbackCenterLat
        val maxLat = lats.maxOrNull() ?: fallbackCenterLat
        val spanLat = max(0.0, maxLat - minLat)
        val centerLat = (minLat + maxLat) / 2.0

        // Longitude bounding with Antimeridian Circular Gap Sorting
        val normLons = points.map { normalizeLongitude(it.longitude) }.distinct().sorted()

        val (centerLon, minLon, maxLon, spanLon, crossesAntimeridian) = calculateLongitudeBounds(normLons)

        // Equirectangular metric spans with spherical cos(lat) scaling
        val midLatRad = Math.toRadians(centerLat)
        val cosLat = max(0.01, cos(midLatRad))

        val spanYMeters = spanLat * METERS_PER_DEGREE_LAT
        val spanXMeters = spanLon * METERS_PER_DEGREE_LON_EQUATOR * cosLat

        // Apply visual clearance padding (padding on both sides)
        val paddedSpanX = spanXMeters * (1.0 + 2.0 * clampedPadding)
        val paddedSpanY = spanYMeters * (1.0 + 2.0 * clampedPadding)

        val halfExtent = max(paddedSpanX / 2.0, paddedSpanY / 2.0).toFloat()
        val calculatedRadius = if (points.size == 1 || (spanXMeters < EPSILON && spanYMeters < EPSILON)) {
            safeMinRadius
        } else {
            halfExtent.coerceIn(safeMinRadius, safeMaxRadius)
        }

        val dynamicRings = calculateDynamicRings(calculatedRadius)
        val ringLabels = formatRingLabels(dynamicRings)

        return ViewportBounds(
            centerLat = centerLat,
            centerLon = centerLon,
            minLat = minLat,
            maxLat = maxLat,
            minLon = minLon,
            maxLon = maxLon,
            spanLat = spanLat,
            spanLon = spanLon,
            spanXMeters = spanXMeters,
            spanYMeters = spanYMeters,
            radiusMeters = calculatedRadius,
            dynamicRingsMeters = dynamicRings,
            ringLabels = ringLabels,
            crossesAntimeridian = crossesAntimeridian
        )
    }

    /**
     * Overload accepting [SituationalPoint] instances directly.
     */
    fun computeViewportFromPoints(
        points: List<SituationalPoint>,
        paddingPercent: Float = DEFAULT_PADDING_PERCENT,
        minRadiusMeters: Float = MIN_RADIUS_METERS,
        maxRadiusMeters: Float = MAX_RADIUS_METERS,
        fallbackCenterLat: Double = 0.0,
        fallbackCenterLon: Double = 0.0
    ): ViewportBounds {
        val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
        return computeViewport(
            points = geoPoints,
            paddingPercent = paddingPercent,
            minRadiusMeters = minRadiusMeters,
            maxRadiusMeters = maxRadiusMeters,
            fallbackCenterLat = fallbackCenterLat,
            fallbackCenterLon = fallbackCenterLon
        )
    }

    /**
     * Fits viewport for a specific canvas aspect ratio (width and height in pixels),
     * ensuring all points fit inside the canvas without clipping in either dimension.
     */
    fun computeViewportForCanvas(
        points: List<GeoPoint>,
        canvasWidthPx: Float,
        canvasHeightPx: Float,
        paddingPercent: Float = DEFAULT_PADDING_PERCENT,
        minRadiusMeters: Float = MIN_RADIUS_METERS,
        maxRadiusMeters: Float = MAX_RADIUS_METERS,
        fallbackCenterLat: Double = 0.0,
        fallbackCenterLon: Double = 0.0
    ): ViewportBounds {
        val baseViewport = computeViewport(
            points = points,
            paddingPercent = paddingPercent,
            minRadiusMeters = minRadiusMeters,
            maxRadiusMeters = maxRadiusMeters,
            fallbackCenterLat = fallbackCenterLat,
            fallbackCenterLon = fallbackCenterLon
        )

        if (canvasWidthPx <= 0f || canvasHeightPx <= 0f || points.size <= 1) {
            return baseViewport
        }

        val clampedPadding = paddingPercent.coerceIn(0.0f, 1.0f)
        val paddedSpanX = baseViewport.spanXMeters * (1.0 + 2.0 * clampedPadding)
        val paddedSpanY = baseViewport.spanYMeters * (1.0 + 2.0 * clampedPadding)

        // For a circular radar canvas, diameter is min(W, H)
        // For general 2D canvas, uniform scale requires:
        // pixelsPerMeter = min(canvasWidthPx / paddedSpanX, canvasHeightPx / paddedSpanY)
        val safePaddedX = max(1.0, paddedSpanX)
        val safePaddedY = max(1.0, paddedSpanY)

        val scaleX = canvasWidthPx / safePaddedX
        val scaleY = canvasHeightPx / safePaddedY
        val scale = min(scaleX, scaleY)

        // Required metric radius from center to edge of the canvas (using min canvas dimension)
        val minCanvasDimension = min(canvasWidthPx, canvasHeightPx)
        val requiredRadiusMeters = if (scale > 0) {
            ((minCanvasDimension / 2.0f) / scale).toFloat()
        } else {
            baseViewport.radiusMeters
        }

        val finalRadius = requiredRadiusMeters.coerceIn(
            max(10f, minRadiusMeters),
            max(minRadiusMeters, maxRadiusMeters)
        )

        val dynamicRings = calculateDynamicRings(finalRadius)
        return baseViewport.copy(
            radiusMeters = finalRadius,
            dynamicRingsMeters = dynamicRings,
            ringLabels = formatRingLabels(dynamicRings)
        )
    }

    /**
     * Resolves the longitudinal bounding interval and center across the antimeridian (±180°)
     * using circular gap sorting (largest gap complement).
     */
    private fun calculateLongitudeBounds(
        sortedUniqueLons: List<Double>
    ): LongitudeBoundsResult {
        if (sortedUniqueLons.isEmpty()) {
            return LongitudeBoundsResult(0.0, 0.0, 0.0, 0.0, false)
        }
        if (sortedUniqueLons.size == 1) {
            val lon = sortedUniqueLons[0]
            return LongitudeBoundsResult(lon, lon, lon, 0.0, false)
        }

        val n = sortedUniqueLons.size
        var maxGap = -1.0
        var maxGapIndex = -1

        // Adjacent gaps
        for (i in 0 until n - 1) {
            val gap = sortedUniqueLons[i + 1] - sortedUniqueLons[i]
            if (gap > maxGap) {
                maxGap = gap
                maxGapIndex = i
            }
        }

        // Circular wrap-around gap across ±180° meridian
        val wrapGap = (sortedUniqueLons[0] + 360.0) - sortedUniqueLons[n - 1]
        if (wrapGap > maxGap) {
            maxGap = wrapGap
            maxGapIndex = n - 1
        }

        return if (maxGapIndex == n - 1) {
            // Points do NOT cross the antimeridian; they lie in [sortedUniqueLons[0], sortedUniqueLons[n-1]]
            val minLon = sortedUniqueLons.first()
            val maxLon = sortedUniqueLons.last()
            val spanLon = maxLon - minLon
            val centerLon = (minLon + maxLon) / 2.0
            LongitudeBoundsResult(centerLon, minLon, maxLon, spanLon, false)
        } else {
            // Points CROSS the antimeridian: largest gap is between index maxGapIndex and maxGapIndex + 1
            // Points occupy the complement: [sortedUniqueLons[maxGapIndex+1], 180) and [-180, sortedUniqueLons[maxGapIndex]]
            val westernmost = sortedUniqueLons[maxGapIndex + 1]
            val easternmost = sortedUniqueLons[maxGapIndex]
            val spanLon = 360.0 - maxGap
            val centerLon = normalizeLongitude(westernmost + spanLon / 2.0)
            LongitudeBoundsResult(centerLon, westernmost, easternmost, spanLon, true)
        }
    }

    private data class LongitudeBoundsResult(
        val centerLon: Double,
        val minLon: Double,
        val maxLon: Double,
        val spanLon: Double,
        val crossesAntimeridian: Boolean
    )

    /**
     * Calculates adaptive concentric distance rings with readable metric intervals.
     */
    fun calculateDynamicRings(radiusMeters: Float): List<Int> {
        val r = radiusMeters.toInt()
        val candidateRings = when {
            r <= 150 -> listOf(25, 50, 100)
            r <= 300 -> listOf(50, 100, 200)
            r <= 750 -> listOf(100, 250, 500)
            r <= 1500 -> listOf(250, 500, 1000)
            r <= 3500 -> listOf(500, 1000, 2500)
            r <= 7500 -> listOf(1000, 2500, 5000)
            r <= 15000 -> listOf(2500, 5000, 10000)
            r <= 35000 -> listOf(5000, 10000, 25000)
            else -> listOf(10000, 25000, 50000)
        }
        val filtered = candidateRings.filter { it <= r }
        return if (filtered.isNotEmpty()) {
            filtered
        } else {
            // Fallback for custom ranges
            listOf(max(10, r / 2), r).distinct()
        }
    }

    /**
     * Formats metric ring values into readable tactical labels (e.g. "100m", "1 km", "2.5 km").
     */
    fun formatRingLabels(ringsMeters: List<Int>): List<String> {
        return ringsMeters.map { formatDistance(it) }
    }

    fun formatDistance(meters: Int): String {
        return when {
            meters < 1000 -> "${meters}m"
            meters % 1000 == 0 -> "${meters / 1000} km"
            else -> {
                val km = meters / 1000.0
                String.format(java.util.Locale.US, "%.1f km", km)
            }
        }
    }
}
