package com.rescuemesh.ai.intelligence

import androidx.compose.ui.geometry.Offset
import com.rescuemesh.ai.ui.screens.ProjectedSituationalPoint
import com.rescuemesh.ai.ui.screens.SituationalPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerClusterEngineTest {

    private fun createProjectedPoint(
        id: String,
        x: Float,
        y: Float,
        type: String,
        status: String? = null,
        lat: Double = 12.9716,
        lon: Double = 77.5946,
        timestamp: Long = 1000L
    ): ProjectedSituationalPoint {
        val point = SituationalPoint(
            id = id,
            latitude = lat,
            longitude = lon,
            label = "Point-$id",
            type = type,
            status = status,
            accuracy = 5f,
            timestamp = timestamp
        )
        return ProjectedSituationalPoint(
            point = point,
            canvasOffset = Offset(x, y),
            distanceMeters = 50f,
            bearingDegrees = 45f,
            isWithinBounds = true,
            accuracyRadiusPx = 10f
        )
    }

    @Test
    fun emptyPointsListReturnsEmptyClusters() {
        val clusters = MarkerClusterEngine.cluster(emptyList())
        assertTrue(clusters.isEmpty())
    }

    @Test
    fun pointsSeparatedBeyondThresholdAreNotClustered() {
        // Point A at (100, 100), Point B at (200, 200) -> distance > 36 dp
        val p1 = createProjectedPoint("p1", 100f, 100f, "NOTE")
        val p2 = createProjectedPoint("p2", 200f, 200f, "HAZARD")

        val clusters = MarkerClusterEngine.cluster(listOf(p1, p2), clusterDistancePx = 36f)

        assertEquals(2, clusters.size)
        assertFalse(clusters[0].isCluster)
        assertFalse(clusters[1].isCluster)
        assertEquals(1, clusters[0].totalCount)
        assertEquals(1, clusters[1].totalCount)
    }

    @Test
    fun pointsWithinThresholdAreMergedIntoSingleCluster() {
        // Point A at (100, 100), Point B at (110, 110) -> distance ≈ 14.14 dp < 36 dp
        val p1 = createProjectedPoint("p1", 100f, 100f, "NOTE")
        val p2 = createProjectedPoint("p2", 110f, 110f, "NOTE")

        val clusters = MarkerClusterEngine.cluster(listOf(p1, p2), clusterDistancePx = 36f)

        assertEquals(1, clusters.size)
        val cluster = clusters.first()
        assertTrue(cluster.isCluster)
        assertEquals(2, cluster.totalCount)
        assertEquals(2, cluster.points.size)
    }

    @Test
    fun emergencySeveritySortingChoosesSosAsRepresentative() {
        // Three points close together: NOTE, HAZARD, and SOS
        val pNote = createProjectedPoint("note1", 100f, 100f, "NOTE")
        val pHazard = createProjectedPoint("haz1", 105f, 105f, "HAZARD")
        val pSos = createProjectedPoint("sos1", 110f, 110f, "SOS")

        // Intentionally provide in reverse priority order
        val clusters = MarkerClusterEngine.cluster(listOf(pNote, pHazard, pSos), clusterDistancePx = 36f)

        assertEquals(1, clusters.size)
        val cluster = clusters.first()
        assertEquals("sos1", cluster.representativePoint.id)
        assertEquals("SOS", cluster.representativePoint.type)
        assertTrue("Cluster must inherit SOS status", cluster.hasSos)
        assertTrue("Cluster must inherit Hazard status", cluster.hasHazard)
        assertEquals(3, cluster.totalCount)
    }

    @Test
    fun routeStatusBlockedHasHigherPriorityThanOpenOrNote() {
        val pNote = createProjectedPoint("note1", 100f, 100f, "NOTE")
        val pOpen = createProjectedPoint("open1", 105f, 105f, "ROUTE_STATUS", status = "OPEN")
        val pBlocked = createProjectedPoint("blocked1", 110f, 110f, "ROUTE_STATUS", status = "BLOCKED")

        val clusters = MarkerClusterEngine.cluster(listOf(pNote, pOpen, pBlocked), clusterDistancePx = 36f)

        assertEquals(1, clusters.size)
        val cluster = clusters.first()
        assertEquals("blocked1", cluster.representativePoint.id)
    }

    @Test
    fun representativePointPreservesExactPhysicalGpsCoordinatesZeroSyntheticCoordinates() {
        // Verify Invariant 5: genuine GPS coordinates must NEVER be altered,
        // averaged, or displaced radially (Spiderfier is strictly forbidden).
        val exactLat = 13.0827
        val exactLon = 80.2707
        val exactOffset = Offset(150f, 250f)

        val pSos = createProjectedPoint(
            id = "sos_real",
            x = exactOffset.x,
            y = exactOffset.y,
            type = "SOS",
            lat = exactLat,
            lon = exactLon
        )
        val pNote = createProjectedPoint(
            id = "note_nearby",
            x = exactOffset.x + 10f,
            y = exactOffset.y + 10f,
            type = "NOTE",
            lat = 13.0829,
            lon = 80.2709
        )

        val clusters = MarkerClusterEngine.cluster(listOf(pSos, pNote), clusterDistancePx = 36f)

        val cluster = clusters.first()
        // Representative GPS must be EXACT physical GPS of the SOS
        assertEquals(exactLat, cluster.representativePoint.latitude, 0.0)
        assertEquals(exactLon, cluster.representativePoint.longitude, 0.0)
        // Screen offset must match exactly
        assertEquals(exactOffset.x, cluster.screenOffset.x, 0.0f)
        assertEquals(exactOffset.y, cluster.screenOffset.y, 0.0f)
    }

    @Test
    fun clusterInheritsGlowFlagsAccurately() {
        // Cluster with only HAZARD
        val pHazard = createProjectedPoint("h1", 100f, 100f, "HAZARD")
        val pNote = createProjectedPoint("n1", 110f, 110f, "NOTE")
        val hazardCluster = MarkerClusterEngine.cluster(listOf(pHazard, pNote)).first()

        assertFalse(hazardCluster.hasSos)
        assertTrue(hazardCluster.hasHazard)

        // Cluster with neither
        val pPeer = createProjectedPoint("peer1", 300f, 300f, "PEER")
        val pNote2 = createProjectedPoint("n2", 310f, 310f, "NOTE")
        val routineCluster = MarkerClusterEngine.cluster(listOf(pPeer, pNote2)).first()

        assertFalse(routineCluster.hasSos)
        assertFalse(routineCluster.hasHazard)
    }

    @Test
    fun underlyingPointsListContainsAllMembersInPriorityOrder() {
        val pPeer = createProjectedPoint("peer1", 50f, 50f, "PEER")
        val pSos = createProjectedPoint("sos1", 52f, 52f, "SOS")
        val pHaz = createProjectedPoint("haz1", 54f, 54f, "HAZARD")

        val cluster = MarkerClusterEngine.cluster(listOf(pPeer, pSos, pHaz)).first()

        assertEquals(3, cluster.points.size)
        assertEquals("SOS", cluster.points[0].type)
        assertEquals("HAZARD", cluster.points[1].type)
        assertEquals("PEER", cluster.points[2].type)
    }

    @Test
    fun findClusterAtDetectsTappedClusterWithinHitRadius() {
        val p1 = createProjectedPoint("p1", 100f, 100f, "SOS")
        val p2 = createProjectedPoint("p2", 300f, 300f, "NOTE")
        val clusters = MarkerClusterEngine.cluster(listOf(p1, p2))

        // Tap near p1 (at 110, 110 -> dist ~ 14px < 36px)
        val hit = MarkerClusterEngine.findClusterAt(clusters, Offset(110f, 110f), hitRadiusPx = 36f)
        assertNotNull(hit)
        assertEquals("p1", hit?.representativePoint?.id)

        // Tap far away (at 500, 500)
        val miss = MarkerClusterEngine.findClusterAt(clusters, Offset(500f, 500f), hitRadiusPx = 36f)
        assertNull(miss)
    }

    @Test
    fun findClusterAtPrioritizesSosWhenTouchOverlapsMultipleClusters() {
        // Two clusters near each other within touch tolerance
        val pSos = createProjectedPoint("sos1", 100f, 100f, "SOS")
        val pNote = createProjectedPoint("note1", 120f, 120f, "NOTE")
        val clusters = MarkerClusterEngine.cluster(listOf(pSos, pNote), clusterDistancePx = 15f)

        // Both are within 40px of tap at (110, 110)
        val tapped = MarkerClusterEngine.findClusterAt(clusters, Offset(110f, 110f), hitRadiusPx = 40f)
        assertNotNull(tapped)
        assertTrue("Tapped cluster should prioritize SOS", tapped?.hasSos == true)
    }

    @Test
    fun hundredOverlappingPointsClusterGracefullyWithoutDegradation() {
        val points = (1..100).map { i ->
            val type = if (i == 42) "SOS" else "NOTE"
            createProjectedPoint("id_$i", 200f + (i % 5), 200f + (i % 5), type)
        }

        val clusters = MarkerClusterEngine.cluster(points, clusterDistancePx = 36f)

        assertEquals(1, clusters.size)
        val cluster = clusters.first()
        assertEquals(100, cluster.totalCount)
        assertTrue(cluster.hasSos)
        assertEquals("id_42", cluster.representativePoint.id)
    }
}
