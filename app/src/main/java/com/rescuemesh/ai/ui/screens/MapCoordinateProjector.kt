package com.rescuemesh.ai.ui.screens

import androidx.compose.ui.geometry.Offset
import com.rescuemesh.ai.data.entity.MapNodeEntity
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Tactical geographic-to-canvas coordinate projector.
 *
 * Projects latitude/longitude coordinates of [MapNodeEntity] vertices onto an Android Canvas
 * coordinate space (x: left -> right, y: top -> bottom) while strictly preserving real-world
 * aspect ratio and physical orientations:
 * - North is oriented toward the top of the canvas (-Y direction).
 * - East is oriented toward the right of the canvas (+X direction).
 * - Distances and geometric angles are scaled uniformly using spherical cos(lat) correction.
 * - Degenerate cases (0 nodes, 1 node, collinear points, zero area) are safely handled without NaN/Inf.
 */
object MapCoordinateProjector {

    const val EPSILON = 1e-7

    /**
     * Project a list of [MapNodeEntity] onto screen canvas coordinates.
     *
     * @param nodes List of surveyed map vertices
     * @param width Total canvas width in pixels
     * @param height Total canvas height in pixels
     * @param paddingLeft Left clearance in pixels
     * @param paddingTop Top clearance in pixels
     * @param paddingRight Right clearance in pixels
     * @param paddingBottom Bottom clearance in pixels (accounting for bottom route panel)
     * @return Map of nodeId to Canvas [Offset] coordinates
     */
    fun project(
        nodes: List<MapNodeEntity>,
        width: Float,
        height: Float,
        paddingLeft: Float = 48f,
        paddingTop: Float = 48f,
        paddingRight: Float = 48f,
        paddingBottom: Float = 140f
    ): Map<String, Offset> {
        if (nodes.isEmpty()) return emptyMap()

        val usableWidth = width - paddingLeft - paddingRight
        val usableHeight = height - paddingTop - paddingBottom

        // Degenerate layout: canvas has zero or negative usable dimensions
        if (usableWidth <= 0f || usableHeight <= 0f) {
            val fallbackCenter = Offset(width / 2f, height / 2f)
            return nodes.associate { it.nodeId to fallbackCenter }
        }

        // Single node: center within usable viewport
        if (nodes.size == 1) {
            val center = Offset(paddingLeft + usableWidth / 2f, paddingTop + usableHeight / 2f)
            return mapOf(nodes.first().nodeId to center)
        }

        val minLat = nodes.minOf { it.latitude }
        val maxLat = nodes.maxOf { it.latitude }
        val minLon = nodes.minOf { it.longitude }
        val maxLon = nodes.maxOf { it.longitude }

        val deltaLat = maxLat - minLat
        val deltaLon = maxLon - minLon

        // Spherical scaling: 1 deg longitude = cos(midLat) * 1 deg latitude in meters
        val midLatRad = Math.toRadians((minLat + maxLat) / 2.0)
        val cosLat = max(0.01, cos(midLatRad))

        val effectiveSpanX = deltaLon * cosLat
        val effectiveSpanY = deltaLat

        // Degenerate case 1: All nodes occupy identical GPS coordinates
        if (effectiveSpanX < EPSILON && effectiveSpanY < EPSILON) {
            val center = Offset(paddingLeft + usableWidth / 2f, paddingTop + usableHeight / 2f)
            return nodes.associate { it.nodeId to center }
        }

        // Degenerate case 2: All nodes along a single latitude (horizontal corridor/line)
        if (effectiveSpanY < EPSILON) {
            val scaleX = (usableWidth / deltaLon).toFloat()
            val y = paddingTop + usableHeight / 2f
            return nodes.associate { node ->
                val x = paddingLeft + ((node.longitude - minLon) * scaleX).toFloat()
                node.nodeId to Offset(x, y)
            }
        }

        // Degenerate case 3: All nodes along a single longitude (vertical corridor/line)
        if (effectiveSpanX < EPSILON) {
            val scaleY = (usableHeight / deltaLat).toFloat()
            val x = paddingLeft + usableWidth / 2f
            return nodes.associate { node ->
                val y = paddingTop + ((maxLat - node.latitude) * scaleY).toFloat()
                node.nodeId to Offset(x, y)
            }
        }

        // General 2D bounding box: compute uniform scale factor preserving physical aspect ratio
        val scaleX = (usableWidth / effectiveSpanX).toFloat()
        val scaleY = (usableHeight / effectiveSpanY).toFloat()
        val scale = min(scaleX, scaleY)

        val contentWidth = (effectiveSpanX * scale).toFloat()
        val contentHeight = (effectiveSpanY * scale).toFloat()

        // Center the scaled bounding box inside the usable canvas area
        val offsetX = paddingLeft + (usableWidth - contentWidth) / 2f
        val offsetY = paddingTop + (usableHeight - contentHeight) / 2f

        return nodes.associate { node ->
            val dx = ((node.longitude - minLon) * cosLat * scale).toFloat()
            val dy = ((maxLat - node.latitude) * scale).toFloat()
            node.nodeId to Offset(offsetX + dx, offsetY + dy)
        }
    }

    /**
     * Projects geographic observation and peer points onto an honest 1 km x 1 km metric situational canvas.
     *
     * @param centerLat Origin latitude (typically device's own GPS or mission centroid)
     * @param centerLon Origin longitude
     * @param points List of geographic points to project
     * @param width Total canvas width in pixels
     * @param height Total canvas height in pixels
     * @param radiusMeters Radius of situational canvas in meters (default 500m for a 1 km x 1 km field)
     * @param paddingPx Padding clearance around canvas perimeter
     * @return [SituationalCanvasLayout] containing scale, metric rings, and clamped/projected points
     */
    fun projectSituationalCanvas(
        centerLat: Double,
        centerLon: Double,
        points: List<SituationalPoint>,
        width: Float,
        height: Float,
        radiusMeters: Float = 500f,
        paddingPx: Float = 36f
    ): SituationalCanvasLayout {
        val centerX = width / 2f
        val centerY = height / 2f
        val centerOffset = Offset(centerX, centerY)

        val usableWidth = max(20f, width - paddingPx * 2f)
        val usableHeight = max(20f, height - paddingPx * 2f)
        val radiusPx = min(usableWidth, usableHeight) / 2f
        val safeRadiusMeters = max(10f, radiusMeters)
        val pixelsPerMeter = radiusPx / safeRadiusMeters

        val standardRings = listOf(100, 250, 500)
        val ringRadiiPx = standardRings
            .filter { it.toFloat() <= safeRadiusMeters }
            .map { it to (it.toFloat() * pixelsPerMeter) }

        val projected = points.map { pt ->
            val deltaLat = pt.latitude - centerLat
            val deltaLon = pt.longitude - centerLon
            val midLatRad = Math.toRadians((centerLat + pt.latitude) / 2.0)
            val cosLat = max(0.01, cos(midLatRad))

            // 1 deg latitude ≈ 111132.95m; 1 deg longitude ≈ 111320m * cos(lat)
            val dy = deltaLat * 111132.95
            val dx = deltaLon * 111320.0 * cosLat

            val dist = kotlin.math.sqrt(dx * dx + dy * dy).toFloat()
            val rad = kotlin.math.atan2(dx, dy)
            val bearing = ((Math.toDegrees(rad) + 360.0) % 360.0).toFloat()
            val isWithin = dist <= safeRadiusMeters

            val canvasPos = if (isWithin) {
                Offset(
                    (centerX + dx * pixelsPerMeter).toFloat(),
                    (centerY - dy * pixelsPerMeter).toFloat()
                )
            } else {
                // Clamp to boundary perimeter for out-of-bounds directional indication
                val normX = if (dist > EPSILON) (dx / dist).toFloat() else 0f
                val normY = if (dist > EPSILON) (dy / dist).toFloat() else 1f
                Offset(
                    centerX + normX * radiusPx,
                    centerY - normY * radiusPx
                )
            }

            val accPx = if (pt.accuracy != null && pt.accuracy > 0f) {
                (pt.accuracy * pixelsPerMeter).coerceIn(4f, radiusPx)
            } else {
                0f
            }

            ProjectedSituationalPoint(
                point = pt,
                canvasOffset = canvasPos,
                distanceMeters = dist,
                bearingDegrees = bearing,
                isWithinBounds = isWithin,
                accuracyRadiusPx = accPx
            )
        }

        return SituationalCanvasLayout(
            centerCanvasOffset = centerOffset,
            radiusPx = radiusPx,
            radiusMeters = safeRadiusMeters,
            pixelsPerMeter = pixelsPerMeter,
            ringRadiiPx = ringRadiiPx,
            projectedPoints = projected
        )
    }
}

/**
 * Representation of a geographic point to render on the tactical situational canvas.
 */
data class SituationalPoint(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val type: String, // "SOS", "HAZARD", "ROUTE_STATUS", "NOTE", "PEER", "USER"
    val status: String? = null,
    val accuracy: Float? = null,
    val timestamp: Long = 0L,
    val isLocal: Boolean = false,
    val rawObservation: Any? = null
)

/**
 * Projected screen representation of a situational point including distance and boundary status.
 */
data class ProjectedSituationalPoint(
    val point: SituationalPoint,
    val canvasOffset: Offset,
    val distanceMeters: Float,
    val bearingDegrees: Float,
    val isWithinBounds: Boolean,
    val accuracyRadiusPx: Float
)

/**
 * Layout metrics for the 1 km x 1 km metric situational canvas.
 */
data class SituationalCanvasLayout(
    val centerCanvasOffset: Offset,
    val radiusPx: Float,
    val radiusMeters: Float,
    val pixelsPerMeter: Float,
    val ringRadiiPx: List<Pair<Int, Float>>,
    val projectedPoints: List<ProjectedSituationalPoint>
)
