package com.rescuemesh.ai.location

import androidx.compose.ui.geometry.Offset
import com.rescuemesh.ai.intelligence.MarkerClusterEngine
import com.rescuemesh.ai.ui.screens.MapCoordinateProjector
import com.rescuemesh.ai.ui.screens.SituationalPoint
import org.junit.Assert.*
import org.junit.Test

/**
 * Validates SituationalCanvas peer projection and clustering compliance:
 * - SituationalPoint supports type == "PEER" with callsign and optional heading bearing.
 * - MapCoordinateProjector projects peer locations into accurate canvas offsets, distances, and bearings.
 * - Out-of-bounds peer beacons are clamped to perimeter with accurate straight-line distances.
 * - MarkerClusterEngine assigns rank 6 to PEER type, preserving emergency priority (SOS > HAZARD > ... > PEER).
 * - AndroidRescueLocationProvider continuous 2-second default configuration.
 */
class SituationalPeerProjectionTest {

    @Test
    fun `situationalPoint supports peer type with bearing and accuracy`() {
        val peerPoint = SituationalPoint(
            id = "peer-alpha-01",
            latitude = 12.9750,
            longitude = 77.5950,
            label = "Medic-1",
            type = "PEER",
            accuracy = 6.0f,
            timestamp = 1700000000000L,
            bearing = 180.0f
        )

        assertEquals("peer-alpha-01", peerPoint.id)
        assertEquals("PEER", peerPoint.type)
        assertEquals("Medic-1", peerPoint.label)
        assertEquals(180.0f, peerPoint.bearing ?: 0f, 0.01f)
        assertEquals(6.0f, peerPoint.accuracy ?: 0f, 0.01f)
        assertEquals(1700000000000L, peerPoint.timestamp)
    }

    @Test
    fun `projectSituationalCanvas projects peer points within 1km radius`() {
        val centerLat = 12.9716
        val centerLon = 77.5946

        // Point ~377 meters North of center
        val northPeer = SituationalPoint(
            id = "peer-north",
            latitude = centerLat + 0.0034,
            longitude = centerLon,
            label = "Scout-North",
            type = "PEER",
            accuracy = 5.0f,
            bearing = 0f
        )

        val layout = MapCoordinateProjector.projectSituationalCanvas(
            centerLat = centerLat,
            centerLon = centerLon,
            points = listOf(northPeer),
            width = 800f,
            height = 800f,
            radiusMeters = 500f,
            paddingPx = 36f
        )

        assertEquals(1, layout.projectedPoints.size)
        val proj = layout.projectedPoints.first()

        assertTrue("Peer within 500m radius must be within bounds", proj.isWithinBounds)
        assertTrue("Distance should be approximately 370-385m", proj.distanceMeters in 370f..385f)
        assertTrue("Bearing should point approximately North (350..360 or 0..10)", proj.bearingDegrees < 10f || proj.bearingDegrees > 350f)
        assertTrue("Accuracy radius in pixels should be positive", proj.accuracyRadiusPx > 0f)
        // North is -Y in screen coordinates (canvasPos.y < center.y)
        assertTrue("North position on canvas must have y < centerY", proj.canvasOffset.y < layout.centerCanvasOffset.y)
    }

    @Test
    fun `projectSituationalCanvas clamps out of bounds peer to perimeter`() {
        val centerLat = 12.9716
        val centerLon = 77.5946

        // Point ~1.5 km away (outside 500m radius)
        val distantPeer = SituationalPoint(
            id = "peer-distant",
            latitude = centerLat + 0.015,
            longitude = centerLon,
            label = "Base-HQ",
            type = "PEER",
            accuracy = 10.0f
        )

        val layout = MapCoordinateProjector.projectSituationalCanvas(
            centerLat = centerLat,
            centerLon = centerLon,
            points = listOf(distantPeer),
            width = 800f,
            height = 800f,
            radiusMeters = 500f,
            paddingPx = 36f
        )

        val proj = layout.projectedPoints.first()
        assertFalse("Distant peer must be marked out of bounds", proj.isWithinBounds)
        assertTrue("Distance should be > 1000m", proj.distanceMeters > 1000f)

        // Clamped offset distance to center should equal layout.radiusPx
        val dx = proj.canvasOffset.x - layout.centerCanvasOffset.x
        val dy = proj.canvasOffset.y - layout.centerCanvasOffset.y
        val distToCenterPx = kotlin.math.sqrt(dx * dx + dy * dy)
        assertEquals(layout.radiusPx, distToCenterPx, 1.0f)
    }

    @Test
    fun `markerClusterEngine clusters nearby peer beacons while preserving emergency priority`() {
        val centerLat = 12.9716
        val centerLon = 77.5946

        // Peer beacon and SOS beacon at almost identical coordinates (<36px)
        val sosPoint = SituationalPoint(
            id = "sos-01",
            latitude = centerLat,
            longitude = centerLon,
            label = "Trapped Civilians",
            type = "SOS"
        )
        val peerPoint = SituationalPoint(
            id = "peer-01",
            latitude = centerLat + 0.00005,
            longitude = centerLon + 0.00005,
            label = "Rescue-Team-2",
            type = "PEER"
        )

        val layout = MapCoordinateProjector.projectSituationalCanvas(
            centerLat = centerLat,
            centerLon = centerLon,
            points = listOf(sosPoint, peerPoint),
            width = 800f,
            height = 800f,
            radiusMeters = 500f
        )

        val clusters = MarkerClusterEngine.cluster(layout.projectedPoints, clusterDistancePx = 50f)
        assertEquals(1, clusters.size)
        val cluster = clusters.first()
        assertTrue("Cluster must contain 2 items", cluster.isCluster)
        assertEquals(2, cluster.totalCount)
        assertTrue("Cluster must flag hasSos", cluster.hasSos)
        // Representative point must be the higher severity SOS, NOT the peer
        assertEquals("sos-01", cluster.representativePoint.id)
        assertEquals("SOS", cluster.representativePoint.type)
        assertEquals(6, MarkerClusterEngine.getSeverityRank(peerPoint))
    }

    @Test
    fun `androidRescueLocationProvider default update interval is 2000ms with 0_5m distance`() {
        assertEquals(2000L, AndroidRescueLocationProvider.DEFAULT_UPDATE_INTERVAL_MS)
        assertEquals(0.5f, AndroidRescueLocationProvider.DEFAULT_MIN_DISTANCE_METERS, 0.001f)
    }
}
