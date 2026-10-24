package com.rescuemesh.ai.safety

import androidx.compose.ui.geometry.Offset
import com.rescuemesh.ai.data.entity.MapEdgeEntity
import com.rescuemesh.ai.data.entity.MapNodeEntity
import com.rescuemesh.ai.fakes.FakeMapDao
import com.rescuemesh.ai.intelligence.AutoFitViewportCalculator
import com.rescuemesh.ai.intelligence.CameraMode
import com.rescuemesh.ai.intelligence.CameraStateMachine
import com.rescuemesh.ai.intelligence.CameraTarget
import com.rescuemesh.ai.intelligence.GeoPoint
import com.rescuemesh.ai.intelligence.MarkerClusterEngine
import com.rescuemesh.ai.intelligence.RoutePlanner
import com.rescuemesh.ai.ui.screens.ProjectedSituationalPoint
import com.rescuemesh.ai.ui.screens.SituationalPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architectural Invariant 5 (Zero Synthetic Routing & Graph Isolation) Verification Suite.
 *
 * Mandate:
 * Raw observation GPS fixes, peer locations, and situational markers must NEVER
 * generate synthetic graph nodes or edges in the topological navigation graph.
 *
 * Verifies:
 * 1. Total Code Isolation: [AutoFitViewportCalculator], [MarkerClusterEngine], and [CameraMode]
 *    do not import or invoke [MapDao], nor do they insert topological nodes or edges.
 * 2. RoutePlanner Invariance: [RoutePlanner] routing results are 100% identical before and after
 *    executing viewport calculations and marker clustering.
 * 3. Physical GPS Coordinate Preservation: Representative points in clusters preserve exact physical
 *    GPS fixes; synthetic radial offsets (Spiderfier) are strictly absent.
 * 4. Route Terminology Safety: No route, segment, or cluster is labeled as "safe".
 */
class GeospatialGraphIsolationTest {

    @Test
    fun testRoutePlannerIsCompletelyUnaffectedByGeospatialCalculations() = runBlocking {
        val testMapDao = FakeMapDao()
        val missionId = "MISSION_GEOSPATIAL_ISOLATION"

        // Set up real surveyed topological graph
        val nodeA = MapNodeEntity(nodeId = "node_A", missionId = missionId, label = "Base Camp", latitude = 12.9716, longitude = 77.5946)
        val nodeB = MapNodeEntity(nodeId = "node_B", missionId = missionId, label = "Checkpoint 1", latitude = 12.9720, longitude = 77.5950)
        val nodeC = MapNodeEntity(nodeId = "node_C", missionId = missionId, label = "Medical Station", latitude = 12.9730, longitude = 77.5960)

        testMapDao.insertNodes(listOf(nodeA, nodeB, nodeC))
        testMapDao.insertEdges(listOf(
            MapEdgeEntity("edge_AB", missionId, "node_A", "node_B", 50f, "REPORTED_OPEN"),
            MapEdgeEntity("edge_BC", missionId, "node_B", "node_C", 100f, "REPORTED_OPEN")
        ))

        val planner = RoutePlanner(testMapDao)

        // Baseline route before any geospatial calculations
        val baselineRoute = planner.findRoute("node_A", "node_C", missionId)
        assertTrue(baselineRoute.isComplete)
        assertEquals(150.0, baselineRoute.totalDistance, 0.001)

        val initialNodeCount = testMapDao.getAllNodes(missionId).size
        val initialEdgeCount = testMapDao.getAllEdges(missionId).size

        // Generate synthetic/raw situational observations (simulating clustered emergency reports)
        val rawObservations = (1..30).map { i ->
            SituationalPoint(
                id = "obs_$i",
                latitude = 12.9725 + (i * 0.0001),
                longitude = 77.5955 + (i * 0.0001),
                label = "Hazard or SOS #$i",
                type = if (i % 3 == 0) "SOS" else "HAZARD"
            )
        }

        // 1. Run AutoFitViewportCalculator
        val viewport = AutoFitViewportCalculator.computeViewportFromPoints(rawObservations)
        assertTrue(viewport.radiusMeters >= AutoFitViewportCalculator.MIN_RADIUS_METERS)

        // 2. Run MarkerClusterEngine
        val projected = rawObservations.mapIndexed { idx, pt ->
            ProjectedSituationalPoint(
                point = pt,
                canvasOffset = Offset(100f + (idx % 5) * 5f, 100f + (idx % 5) * 5f),
                distanceMeters = 75f,
                bearingDegrees = 90f,
                isWithinBounds = true,
                accuracyRadiusPx = 5f
            )
        }
        val clusters = MarkerClusterEngine.cluster(projected)
        assertTrue(clusters.isNotEmpty())

        // 3. Run CameraStateMachine
        val cameraMachine = CameraStateMachine()
        cameraMachine.onSelectTarget(CameraTarget("obs_1", 12.9725, 77.5955))
        cameraMachine.onUserLocationUpdated(12.9716, 77.5946)

        // Re-verify that MapDao topology was NOT mutated in any way
        val afterNodeCount = testMapDao.getAllNodes(missionId).size
        val afterEdgeCount = testMapDao.getAllEdges(missionId).size

        assertEquals("No nodes should ever be inserted by geospatial calculations", initialNodeCount, afterNodeCount)
        assertEquals("No edges should ever be inserted by geospatial calculations", initialEdgeCount, afterEdgeCount)

        // Re-verify route planner output is 100% identical
        val postRoute = planner.findRoute("node_A", "node_C", missionId)
        assertEquals(baselineRoute.isComplete, postRoute.isComplete)
        assertEquals(baselineRoute.totalDistance, postRoute.totalDistance, 0.0001)
        assertEquals(baselineRoute.path, postRoute.path)
        assertEquals(baselineRoute.pathLabels, postRoute.pathLabels)
    }

    @Test
    fun testRepresentativePointPreservesExactCoordinatesWithoutSyntheticOffsets() {
        // Anti-Spiderfier verification:
        // Cluster must NOT displace representative coordinates or generate dummy circle points
        val origLat = 12.9715987
        val origLon = 77.5945621
        val pt = SituationalPoint(
            id = "sos_real",
            latitude = origLat,
            longitude = origLon,
            label = "Real Trapped Survivor",
            type = "SOS"
        )
        val proj = ProjectedSituationalPoint(
            point = pt,
            canvasOffset = Offset(250f, 400f),
            distanceMeters = 10f,
            bearingDegrees = 180f,
            isWithinBounds = true,
            accuracyRadiusPx = 2f
        )

        val clusters = MarkerClusterEngine.cluster(listOf(proj))
        val cluster = clusters.first()

        assertEquals("Latitude must be exact physical coordinate", origLat, cluster.representativePoint.latitude, 0.0)
        assertEquals("Longitude must be exact physical coordinate", origLon, cluster.representativePoint.longitude, 0.0)
        assertEquals(250f, cluster.screenOffset.x, 0.0f)
        assertEquals(400f, cluster.screenOffset.y, 0.0f)
    }

    @Test
    fun testSentinelSafetyDisclaimerMandate() {
        val expected = "Straight-line proximity only. No surveyed walking route available. Watch for unmapped hazards."
        assertEquals(expected, AutoFitViewportCalculator.SAFETY_DISCLAIMER)
        assertEquals(expected, MarkerClusterEngine.SAFETY_DISCLAIMER)
    }

    @Test
    fun testRoutePlannerExcludesBlockedAndConflictedEdgesUnderSentinelMandate() = runBlocking {
        val testMapDao = FakeMapDao()
        val missionId = "MISSION_SENTINEL_EXCLUSION"

        val n1 = MapNodeEntity(nodeId = "n1", missionId = missionId, label = "Start Point", latitude = 10.0, longitude = 20.0)
        val n2 = MapNodeEntity(nodeId = "n2", missionId = missionId, label = "Blocked Midpoint", latitude = 10.01, longitude = 20.01)
        val n3 = MapNodeEntity(nodeId = "n3", missionId = missionId, label = "Conflicted Midpoint", latitude = 10.02, longitude = 20.02)
        val nEnd = MapNodeEntity(nodeId = "nEnd", missionId = missionId, label = "Destination", latitude = 10.03, longitude = 20.03)

        testMapDao.insertNodes(listOf(n1, n2, n3, nEnd))

        // Path 1 via n2 is REPORTED_BLOCKED
        testMapDao.insertEdges(listOf(
            MapEdgeEntity("e1_2", missionId, "n1", "n2", 20f, "REPORTED_OPEN"),
            MapEdgeEntity("e2_end", missionId, "n2", "nEnd", 20f, "REPORTED_BLOCKED")
        ))
        // Path 2 via n3 is CONFLICTED
        testMapDao.insertEdges(listOf(
            MapEdgeEntity("e1_3", missionId, "n1", "n3", 25f, "REPORTED_OPEN"),
            MapEdgeEntity("e3_end", missionId, "n3", "nEnd", 25f, "CONFLICTED")
        ))

        val planner = RoutePlanner(testMapDao)

        // When avoidBlocked = true and avoidConflicted = true (Sentinel G3-R04 mandate)
        val route = planner.findRoute("n1", "nEnd", missionId, avoidBlocked = true, avoidConflicted = true)

        assertFalse("Route through blocked and conflicted edges must be rejected", route.isComplete)
        assertTrue(route.path.isEmpty())
    }

    @Test
    fun testStaticCodebaseIsolationAudit() {
        val baseDir = File("src/main").let { if (it.exists()) it else File("app/src/main") }
        assertTrue("Source base directory must exist", baseDir.exists())

        val targetFiles = listOf(
            File(baseDir, "java/com/rescuemesh/ai/intelligence/AutoFitViewportCalculator.kt"),
            File(baseDir, "java/com/rescuemesh/ai/intelligence/MarkerClusterEngine.kt"),
            File(baseDir, "java/com/rescuemesh/ai/intelligence/CameraMode.kt")
        )

        for (file in targetFiles) {
            assertTrue("File must exist: ${file.path}", file.exists())
            val content = file.readText()

            // Verify no MapDao or graph entity imports
            assertFalse(
                "File ${file.name} must not import MapDao",
                content.contains("import com.rescuemesh.ai.data.dao.MapDao")
            )
            assertFalse(
                "File ${file.name} must not import MapEdgeEntity",
                content.contains("import com.rescuemesh.ai.data.entity.MapEdgeEntity")
            )
            assertFalse(
                "File ${file.name} must not import MapNodeEntity",
                content.contains("import com.rescuemesh.ai.data.entity.MapNodeEntity")
            )
            assertFalse(
                "File ${file.name} must not import RoutePlanner",
                content.contains("import com.rescuemesh.ai.intelligence.RoutePlanner")
            )

            // Verify no forbidden Spiderfier references
            assertFalse(
                "File ${file.name} must not use spiderfier",
                content.contains("spiderfier", ignoreCase = true)
            )

            // Verify Invariant 4: No routes labeled as "safe"
            assertFalse(
                "File ${file.name} must not describe routes or paths as safe",
                content.contains("safe route", ignoreCase = true) ||
                        content.contains("safe path", ignoreCase = true) ||
                        content.contains("\"safe\"", ignoreCase = true)
            )
        }
    }
}
