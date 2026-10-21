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
}
