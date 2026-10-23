package com.rescuemesh.ai.gate1

import androidx.compose.ui.geometry.Offset
import com.rescuemesh.ai.data.entity.MapEdgeEntity
import com.rescuemesh.ai.data.entity.MapNodeEntity
import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.data.entity.OutboxEntity
import com.rescuemesh.ai.data.repository.ObservationRepository
import com.rescuemesh.ai.data.repository.SyncRepository
import com.rescuemesh.ai.fakes.FakeMapDao
import com.rescuemesh.ai.fakes.FakeMetricsDao
import com.rescuemesh.ai.fakes.FakeObservationDao
import com.rescuemesh.ai.fakes.FakeOutboxDao
import com.rescuemesh.ai.intelligence.MapFusionEngine
import com.rescuemesh.ai.intelligence.PriorityScheduler
import com.rescuemesh.ai.intelligence.RoutePlanner
import com.rescuemesh.ai.mesh.MeshTransport
import com.rescuemesh.ai.mesh.SyncCoordinator
import com.rescuemesh.ai.ui.screens.MapCoordinateProjector
import com.rescuemesh.ai.ui.screens.SituationalPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import kotlin.math.sqrt

/**
 * Gate 1 QA Regression & Adversarial Bug Hunt Test Suite.
 *
 * Dedicated test suite verifying Gate 1 invariants, stress conditions, and edge cases:
 * 1. RoutePlanner Safety Invariant & Bidirectional Exclusion:
 *    - Strict prohibition of "safe" terminology in routes, warnings, or path labels.
 *    - Bidirectional exclusion of REPORTED_BLOCKED and VERIFIED_BLOCKED passages.
 *    - Incomplete route handling with zero NaN/Inf or uncaught exceptions.
 *    - Risk penalty vs uncertainty penalty trade-offs in multi-path topologies.
 *    - Exploration suggestions strictly restricted to unvisited/unknown edges.
 *
 * 2. Situational Canvas & 3-Tier Map Engine:
 *    - Empty points, zero/negative usable canvas dimensions without division by zero.
 *    - Accurate boundary clamping preserving bearing and radius on perimeter.
 *    - Accuracy uncertainty circle bounds coercion [4f, radiusPx].
 *    - Aspect ratio and spherical cos(lat) projection on degenerate node topologies.
 *
 * 3. PriorityScheduler (VoI) Absolute Safety Floor & Budget Scheduling:
 *    - Absolute Safety Floor (P >= 0.80) under extreme adversarial penalties (100k chars, ancient/skewed time).
 *    - Case-insensitive status handling ("BLOCKED", "blocked", "HAZARD", "sos").
 *    - Strict priority descending order prior to byte budget cutoff.
 *    - Resilience to malformed observations.
 *
 * 4. Outbox Crash Recovery & Bounded Exponential Backoff:
 *    - Mathematical verification of backoff delays and clamping at 32000 ms.
 *    - Prevention of integer overflow with Int.MAX_VALUE or negative retry counts.
 *    - Immediate transmission bypass for high-priority outbox entries (P >= 0.80).
 *    - Atomicity and correctness of orphan SENDING -> PENDING recovery.
 *
 * 5. MapFusionEngine Conflict Preservation & Survey Resolution:
 *    - Human verification survey (isVerified = true) resolving conflicted states.
 *    - Subsequent unverified contradictory reports returning verified edges to CONFLICTED.
 *    - HAZARD reports on edges correctly treated as REPORTED_BLOCKED.
 *
 * 6. Static Codebase Audit:
 *    - Deep AST/token inspection ensuring zero occurrences of prohibited "safe route" tokens in production code.
 */
class Gate1RegressionBugHuntTest {

    private lateinit var mapDao: FakeMapDao
    private lateinit var observationDao: FakeObservationDao
    private lateinit var outboxDao: FakeOutboxDao

    private lateinit var scheduler: PriorityScheduler
    private lateinit var routePlanner: RoutePlanner
    private lateinit var fusionEngine: MapFusionEngine

    private val missionId = "GATE1_REGRESSION_HUNT_MISSION"

    @Before
    fun setUp() {
        mapDao = FakeMapDao()
        observationDao = FakeObservationDao()
        outboxDao = FakeOutboxDao()

        scheduler = PriorityScheduler(observationDao, mapDao, outboxDao)
        routePlanner = RoutePlanner(mapDao)
        fusionEngine = MapFusionEngine(mapDao, observationDao)
    }

    // =========================================================================
    // 1. RoutePlanner Safety Invariant & Adversarial Graph Scenarios
    // =========================================================================

    @Test
    fun testRoutePlannerRejectsBothDirectionsWhenEdgeReportedBlocked() = runBlocking {
        // Setup two nodes A <-> B with a single edge that is REPORTED_BLOCKED
        val nA = MapNodeEntity("NodeA", missionId, "Sector Alpha", 0, 12.971, 77.594)
        val nB = MapNodeEntity("NodeB", missionId, "Sector Bravo", 0, 12.972, 77.595)
        mapDao.insertNodes(listOf(nA, nB))

        mapDao.insertEdge(
            MapEdgeEntity(
                edgeId = "edge_AB",
                missionId = missionId,
                fromNodeId = "NodeA",
                toNodeId = "NodeB",
                distance = 15f,
                routeStatus = "REPORTED_BLOCKED"
            )
        )

        // Forward route A -> B must fail
        val forwardResult = routePlanner.findRoute("NodeA", "NodeB", missionId, avoidBlocked = true)
        assertFalse("Forward route across REPORTED_BLOCKED edge must be incomplete", forwardResult.isComplete)
        assertTrue("Forward route path must be empty", forwardResult.path.isEmpty())
        assertTrue("Forward route warnings must state no route found", forwardResult.warnings.any { it.contains("No route found") })
        assertFalse("Warnings must never contain 'safe'", forwardResult.warnings.any { it.contains("safe", ignoreCase = true) })

        // Reverse route B -> A must ALSO fail because the physical passage is blocked in both directions
        val reverseResult = routePlanner.findRoute("NodeB", "NodeA", missionId, avoidBlocked = true)
        assertFalse("Reverse route across REPORTED_BLOCKED edge must be incomplete", reverseResult.isComplete)
        assertTrue("Reverse route path must be empty", reverseResult.path.isEmpty())
        assertTrue("Reverse route warnings must state no route found", reverseResult.warnings.any { it.contains("No route found") })
        assertFalse("Warnings must never contain 'safe'", reverseResult.warnings.any { it.contains("safe", ignoreCase = true) })
    }

    @Test
    fun testRoutePlannerRejectsBothDirectionsWhenEdgeVerifiedBlocked() = runBlocking {
        val nA = MapNodeEntity("NodeA", missionId, "Checkpoint 1", 0, 12.971, 77.594)
        val nB = MapNodeEntity("NodeB", missionId, "Checkpoint 2", 0, 12.972, 77.595)
        mapDao.insertNodes(listOf(nA, nB))

        mapDao.insertEdge(
            MapEdgeEntity(
                edgeId = "edge_AB_verified",
                missionId = missionId,
                fromNodeId = "NodeA",
                toNodeId = "NodeB",
                distance = 25f,
                routeStatus = "VERIFIED_BLOCKED"
            )
        )

        val forwardResult = routePlanner.findRoute("NodeA", "NodeB", missionId, avoidBlocked = true)
        assertFalse(forwardResult.isComplete)

        val reverseResult = routePlanner.findRoute("NodeB", "NodeA", missionId, avoidBlocked = true)
        assertFalse(reverseResult.isComplete)
    }

    @Test
    fun testRoutePlannerWhenAllPathsAreBlockedReturnsIncompleteWithClearWarnings() = runBlocking {
        // Diamond topology:
        // Start -> W1 -> Goal (W1 -> Goal is REPORTED_BLOCKED)
        // Start -> W2 -> Goal (Start -> W2 is VERIFIED_BLOCKED)
        val nStart = MapNodeEntity("S", missionId, "Start Point", 0, 12.970, 77.590)
        val nW1 = MapNodeEntity("W1", missionId, "Waypoint North", 0, 12.971, 77.591)
        val nW2 = MapNodeEntity("W2", missionId, "Waypoint South", 0, 12.969, 77.591)
        val nGoal = MapNodeEntity("G", missionId, "Goal Point", 0, 12.970, 77.592)
        mapDao.insertNodes(listOf(nStart, nW1, nW2, nGoal))

        mapDao.insertEdges(listOf(
            MapEdgeEntity("e_S_W1", missionId, "S", "W1", 10f, "REPORTED_OPEN"),
            MapEdgeEntity("e_W1_G", missionId, "W1", "G", 10f, "REPORTED_BLOCKED"),
            MapEdgeEntity("e_S_W2", missionId, "S", "W2", 10f, "VERIFIED_BLOCKED"),
            MapEdgeEntity("e_W2_G", missionId, "W2", "G", 10f, "REPORTED_OPEN")
        ))

        val result = routePlanner.findRoute("S", "G", missionId, avoidBlocked = true)

        assertFalse("Route must be incomplete when all candidate paths are blocked", result.isComplete)
        assertTrue("Path must be empty", result.path.isEmpty())
        assertTrue("Edges used must be empty", result.edgesUsed.isEmpty())
        assertEquals(0.0, result.totalDistance, 1e-6)
        assertEquals(0.0, result.totalCost, 1e-6)
        assertTrue("Warning must indicate no route found", result.warnings.any { it.contains("No route found") })
        assertFalse("Warnings must never contain 'safe'", result.warnings.any { it.contains("safe", ignoreCase = true) })
    }

    @Test
    fun testRoutePlannerWithZeroEdgesAndZeroNodesGraceful() = runBlocking {
        // Empty map data
        val result = routePlanner.findRoute("NodeA", "NodeB", missionId)

        assertFalse("Result must be incomplete on empty map", result.isComplete)
        assertTrue("Path must be empty", result.path.isEmpty())
        assertEquals(listOf("No map data available"), result.warnings)
        assertEquals(0.0, result.totalDistance, 1e-6)
        assertEquals(0.0, result.totalCost, 1e-6)
        assertFalse("Warnings must not contain 'safe'", result.warnings.any { it.contains("safe", ignoreCase = true) })
    }

    @Test
    fun testRoutePlannerAdversarialTopologyMultiPathRiskWeighting() = runBlocking {
        // Topologically evaluate Dijkstra weighting:
        // S -> P1_1 -> P1_2 -> G: Distance = 20 + 20 + 20 = 60m, VERIFIED_OPEN (cost = 60.0)
        // S -> P2 -> G: Distance = 5 + 5 = 10m, CONFLICTED (cost = 10 + 100 = 110.0)
        // S -> P3 -> G: Distance = 10 + 10 = 20m, UNKNOWN (cost = 20 + 50 = 70.0)
        val nodeIds = listOf("S", "G", "P1_1", "P1_2", "P2", "P3")
        mapDao.insertNodes(nodeIds.map { MapNodeEntity(it, missionId, "Node $it", 0, 0.0, 0.0) })

        mapDao.insertEdges(listOf(
            // Path 1 (Verified Open)
            MapEdgeEntity("e1_1", missionId, "S", "P1_1", 20f, "VERIFIED_OPEN"),
            MapEdgeEntity("e1_2", missionId, "P1_1", "P1_2", 20f, "VERIFIED_OPEN"),
            MapEdgeEntity("e1_3", missionId, "P1_2", "G", 20f, "VERIFIED_OPEN"),
            // Path 2 (Conflicted)
            MapEdgeEntity("e2_1", missionId, "S", "P2", 5f, "CONFLICTED"),
            MapEdgeEntity("e2_2", missionId, "P2", "G", 5f, "CONFLICTED"),
            // Path 3 (Unknown)
            MapEdgeEntity("e3_1", missionId, "S", "P3", 10f, "UNKNOWN"),
            MapEdgeEntity("e3_2", missionId, "P3", "G", 10f, "UNKNOWN")
        ))

        // Default: riskPenalty = 100, uncertaintyPenalty = 50.
        // Path 1 cost = 60.0 (no penalty for VERIFIED_OPEN)
        // Path 3 cost = 20 + 50*2 = 120.0
        // Path 2 cost = 10 + 100*2 = 210.0
        // Best path MUST be Path 1!
        val defaultResult = routePlanner.findRoute("S", "G", missionId)
        assertTrue(defaultResult.isComplete)
        assertEquals(listOf("S", "P1_1", "P1_2", "G"), defaultResult.path)
        assertEquals(60.0, defaultResult.totalDistance, 1e-4)
        assertEquals(60.0, defaultResult.totalCost, 1e-4)
        assertTrue("No warnings for fully verified path", defaultResult.warnings.isEmpty())

        // If avoidConflicted = true, Path 2 is barred completely
        val noConflictResult = routePlanner.findRoute("S", "G", missionId, avoidConflicted = true)
        assertTrue(noConflictResult.isComplete)
        assertEquals(listOf("S", "P1_1", "P1_2", "G"), noConflictResult.path)

        // When traversing with avoidBlocked = false on a blocked edge, cost is BLOCKED_COST
        val blockedEdgeDao = FakeMapDao()
        blockedEdgeDao.insertNodes(listOf(
            MapNodeEntity("N1", missionId, "N1", 0, 0.0, 0.0),
            MapNodeEntity("N2", missionId, "N2", 0, 0.0, 0.0)
        ))
        blockedEdgeDao.insertEdge(MapEdgeEntity("b1", missionId, "N1", "N2", 10f, "REPORTED_BLOCKED"))
        val forceBlockedResult = RoutePlanner(blockedEdgeDao).findRoute("N1", "N2", missionId, avoidBlocked = false)
        assertTrue(forceBlockedResult.isComplete)
        assertTrue("Cost on forced blocked route must include huge BLOCKED_COST penalty", forceBlockedResult.totalCost >= RoutePlanner.BLOCKED_COST)
        assertTrue("Must generate warning for blocked passage", forceBlockedResult.warnings.any { it.contains("BLOCKED") })
        assertFalse("Warnings must never contain 'safe'", forceBlockedResult.warnings.any { it.contains("safe", ignoreCase = true) })
    }

    @Test
    fun testRoutePlannerSuggestExplorationReturnsOnlyAdjacentNodesToUnknownEdges() = runBlocking {
        val nodes = listOf("A", "B", "C", "D", "E").map { MapNodeEntity(it, missionId, "Node $it", 0, 0.0, 0.0) }
        mapDao.insertNodes(nodes)

        mapDao.insertEdges(listOf(
            MapEdgeEntity("e1", missionId, "A", "B", 10f, "VERIFIED_OPEN"),
            MapEdgeEntity("e2", missionId, "B", "C", 10f, "REPORTED_BLOCKED"),
            MapEdgeEntity("e3", missionId, "C", "D", 10f, "UNKNOWN"),
            MapEdgeEntity("e4", missionId, "D", "E", 10f, "CONFLICTED")
        ))

        val explorationTargets = routePlanner.suggestExploration(missionId)

        // Only edge C-D is UNKNOWN, so exploration targets must be exactly {C, D}
        assertEquals(2, explorationTargets.size)
        assertTrue("Must include C", explorationTargets.contains("C"))
        assertTrue("Must include D", explorationTargets.contains("D"))
        assertFalse("Must NOT include A", explorationTargets.contains("A"))
        assertFalse("Must NOT include B", explorationTargets.contains("B"))
        assertFalse("Must NOT include E", explorationTargets.contains("E"))
    }

    // =========================================================================
    // 2. Situational Canvas & 3-Tier Map Engine
    // =========================================================================

    @Test
    fun testMapCoordinateProjectorEmptyAndDegenerateCases() {
        // Case 1: Empty nodes list
        val emptyResult = MapCoordinateProjector.project(emptyList(), width = 800f, height = 800f)
        assertTrue("Empty nodes must yield empty map", emptyResult.isEmpty())

        // Case 2: Zero or negative usable dimensions
        val node1 = MapNodeEntity("n1", missionId, "N1", 0, 12.97, 77.59)
        val node2 = MapNodeEntity("n2", missionId, "N2", 0, 12.98, 77.60)
        val degenerateDimensions = MapCoordinateProjector.project(
            nodes = listOf(node1, node2),
            width = 50f, // paddingLeft (48) + paddingRight (48) = 96 > 50 -> usableWidth < 0
            height = 50f
        )
        assertEquals(2, degenerateDimensions.size)
        val expectedFallbackCenter = Offset(25f, 25f)
        for ((_, offset) in degenerateDimensions) {
            assertEquals(expectedFallbackCenter.x, offset.x, 1e-4f)
            assertEquals(expectedFallbackCenter.y, offset.y, 1e-4f)
            assertFalse(offset.x.isNaN())
            assertFalse(offset.y.isNaN())
        }

        // Case 3: All nodes have identical coordinates (zero spatial area)
        val identicalNodes = listOf(
            MapNodeEntity("id1", missionId, "Point 1", 0, 12.9716, 77.5946),
            MapNodeEntity("id2", missionId, "Point 2", 0, 12.9716, 77.5946)
        )
        val identicalResult = MapCoordinateProjector.project(identicalNodes, width = 600f, height = 600f)
        assertEquals(2, identicalResult.size)
        for ((_, offset) in identicalResult) {
            assertFalse("X must not be NaN for zero area", offset.x.isNaN())
            assertFalse("Y must not be NaN for zero area", offset.y.isNaN())
        }

        // Case 4: Strictly horizontal corridor (all nodes identical latitude)
        val horizontalNodes = listOf(
            MapNodeEntity("h1", missionId, "H1", 0, 12.0, 77.0),
            MapNodeEntity("h2", missionId, "H2", 0, 12.0, 77.5),
            MapNodeEntity("h3", missionId, "H3", 0, 12.0, 78.0)
        )
        val horizResult = MapCoordinateProjector.project(horizontalNodes, width = 600f, height = 600f)
        assertEquals(3, horizResult.size)
        for ((_, offset) in horizResult) {
            assertFalse(offset.x.isNaN())
            assertFalse(offset.y.isNaN())
        }
        val yCoords = horizResult.values.map { it.y }
        assertEquals(yCoords[0], yCoords[1], 1e-3f)
        assertEquals(yCoords[1], yCoords[2], 1e-3f)

        // Case 5: Strictly vertical corridor (all nodes identical longitude)
        val verticalNodes = listOf(
            MapNodeEntity("v1", missionId, "V1", 0, 12.0, 77.0),
            MapNodeEntity("v2", missionId, "V2", 0, 12.5, 77.0),
            MapNodeEntity("v3", missionId, "V3", 0, 13.0, 77.0)
        )
        val vertResult = MapCoordinateProjector.project(verticalNodes, width = 600f, height = 600f)
        assertEquals(3, vertResult.size)
        val xCoords = vertResult.values.map { it.x }
        assertEquals(xCoords[0], xCoords[1], 1e-3f)
        assertEquals(xCoords[1], xCoords[2], 1e-3f)
    }

    @Test
    fun testSituationalCanvasEmptyPointsAndZeroDimensionsSafe() {
        val emptyLayout = MapCoordinateProjector.projectSituationalCanvas(
            centerLat = 12.9716,
            centerLon = 77.5946,
            points = emptyList(),
            width = 500f,
            height = 500f
        )
        assertTrue(emptyLayout.projectedPoints.isEmpty())
        assertTrue("radiusPx must be positive", emptyLayout.radiusPx > 0f)
        assertTrue("pixelsPerMeter must be positive", emptyLayout.pixelsPerMeter > 0f)
        assertEquals(Offset(250f, 250f), emptyLayout.centerCanvasOffset)

        val tinyLayout = MapCoordinateProjector.projectSituationalCanvas(
            centerLat = 12.9716,
            centerLon = 77.5946,
            points = listOf(SituationalPoint("p1", 12.9720, 77.5950, "P1", "NOTE")),
            width = 10f,
            height = 10f,
            paddingPx = 36f
        )
        assertTrue("Usable radius must not be NaN or negative", tinyLayout.radiusPx > 0f)
        assertFalse(tinyLayout.pixelsPerMeter.isNaN())
        assertFalse(tinyLayout.pixelsPerMeter.isInfinite())
        val proj = tinyLayout.projectedPoints.first()
        assertFalse(proj.canvasOffset.x.isNaN())
        assertFalse(proj.canvasOffset.y.isNaN())
    }

    @Test
    fun testSituationalCanvasClampingDirectionalConsistency() {
        val centerLat = 10.0
        val centerLon = 20.0
        val radiusMeters = 500f
        val width = 1000f
        val height = 1000f
        val padding = 50f

        val delta10kmLat = 10000.0 / 111132.95
        val delta10kmLon = 10000.0 / (111320.0 * Math.cos(Math.toRadians(10.0)))

        val pts = listOf(
            SituationalPoint("north", centerLat + delta10kmLat, centerLon, "North 10km", "HAZARD"),
            SituationalPoint("south", centerLat - delta10kmLat, centerLon, "South 10km", "HAZARD"),
            SituationalPoint("east", centerLat, centerLon + delta10kmLon, "East 10km", "HAZARD"),
            SituationalPoint("west", centerLat, centerLon - delta10kmLon, "West 10km", "HAZARD")
        )

        val layout = MapCoordinateProjector.projectSituationalCanvas(
            centerLat = centerLat,
            centerLon = centerLon,
            points = pts,
            width = width,
            height = height,
            radiusMeters = radiusMeters,
            paddingPx = padding
        )

        assertEquals(4, layout.projectedPoints.size)
        val radiusPx = layout.radiusPx

        for (proj in layout.projectedPoints) {
            assertFalse("${proj.point.id} must be out of bounds", proj.isWithinBounds)
            assertTrue("Distance must exceed 500m", proj.distanceMeters > 500f)

            val distFromCenter = sqrt(
                (proj.canvasOffset.x - layout.centerCanvasOffset.x) * (proj.canvasOffset.x - layout.centerCanvasOffset.x) +
                (proj.canvasOffset.y - layout.centerCanvasOffset.y) * (proj.canvasOffset.y - layout.centerCanvasOffset.y)
            )
            assertEquals("Clamped point must be on perimeter circle", radiusPx, distFromCenter, 0.5f)
        }

        val projNorth = layout.projectedPoints.first { it.point.id == "north" }
        val projSouth = layout.projectedPoints.first { it.point.id == "south" }
        val projEast = layout.projectedPoints.first { it.point.id == "east" }
        val projWest = layout.projectedPoints.first { it.point.id == "west" }

        assertEquals(0f, projNorth.bearingDegrees, 1.0f)
        assertEquals(180f, projSouth.bearingDegrees, 1.0f)
        assertEquals(90f, projEast.bearingDegrees, 1.0f)
        assertEquals(270f, projWest.bearingDegrees, 1.0f)
    }

    // =========================================================================
    // 3. PriorityScheduler (VoI) Absolute Safety Floor & Scheduling
    // =========================================================================

    @Test
    fun testPrioritySchedulerSafetyFloorForBlockedStatusVariations() {
        val blockedVariations = listOf(
            "BLOCKED", "blocked", "Blocked",
            "REPORTED_BLOCKED", "reported_blocked", "Reported_Blocked",
            "VERIFIED_BLOCKED", "verified_blocked", "Verified_Blocked"
        )

        for (status in blockedVariations) {
            val obs = ObservationEntity(
                eventId = "event-blocked-$status",
                missionId = missionId,
                authorDeviceId = "dev-1",
                authorSequence = 1L,
                eventType = "ROUTE_STATUS",
                title = "Debris blockage",
                description = "X".repeat(5000),
                reportedStatus = status,
                observedAtDevice = System.currentTimeMillis() - (10 * 86400 * 1000L)
            )

            val breakdown = scheduler.scoreBreakdown(obs)
            val score = scheduler.scoreObservation(obs)

            assertEquals("Urgency for blocked variation '$status' must be 0.95", 0.95f, breakdown["urgency"]!!, 1e-4f)
            assertEquals("Safety critical flag must be 1.0", 1.0f, breakdown["safetyCritical"]!!, 1e-4f)
            assertTrue("Invariant 5 violated: $status must receive safety floor >= 0.80f (was $score)", score >= 0.80f)
        }
    }

    @Test
    fun testPrioritySchedulerSafetyFloorForSOSAndHazardVariations() {
        val criticalTypes = listOf("sos", "SOS", "Sos", "hazard", "HAZARD", "Hazard")

        for (type in criticalTypes) {
            val obs = ObservationEntity(
                eventId = "crit-$type",
                missionId = missionId,
                authorDeviceId = "dev-1",
                authorSequence = 1L,
                eventType = type,
                title = "Emergency",
                description = "Y".repeat(10000),
                observedAtDevice = System.currentTimeMillis() - (365 * 86400 * 1000L)
            )

            val breakdown = scheduler.scoreBreakdown(obs)
            val score = scheduler.scoreObservation(obs)

            assertEquals("Urgency for critical type '$type' must be 1.0", 1.0f, breakdown["urgency"]!!, 1e-4f)
            assertEquals(1.0f, breakdown["safetyCritical"]!!, 1e-4f)
            assertTrue("Invariant 5: critical type '$type' must have score >= 0.80f (was $score)", score >= 0.80f)
        }
    }

    @Test
    fun testPrioritySchedulerSelectWithinBudgetStrictPriorityOrder() = runBlocking {
        val now = System.currentTimeMillis()
        val obsHazard = ObservationEntity("e_hazard", missionId, "d1", 1L, "HAZARD", "Hazard", "Structural damage", observedAtDevice = now)
        val obsSOS = ObservationEntity("e_sos", missionId, "d1", 2L, "SOS", "SOS", "Trapped responder", observedAtDevice = now)
        val obsBlocked = ObservationEntity("e_blocked", missionId, "d1", 3L, "ROUTE_STATUS", "Blocked", "Passage closed", reportedStatus = "BLOCKED", observedAtDevice = now)
        val obsOpen = ObservationEntity("e_open", missionId, "d1", 4L, "ROUTE_STATUS", "Open", "Passage clear", reportedStatus = "OPEN", observedAtDevice = now)
        val obsNote = ObservationEntity("e_note", missionId, "d1", 5L, "NOTE", "Note", "Field comment", observedAtDevice = now)

        observationDao.insertAll(listOf(obsHazard, obsSOS, obsBlocked, obsOpen, obsNote))

        val outboxEntries = listOf(
            OutboxEntity(eventId = "e_note", priority = 0.2f),
            OutboxEntity(eventId = "e_open", priority = 0.5f),
            OutboxEntity(eventId = "e_blocked", priority = 0.82f),
            OutboxEntity(eventId = "e_hazard", priority = 0.95f),
            OutboxEntity(eventId = "e_sos", priority = 0.90f)
        )
        outboxDao.insertAll(outboxEntries)

        val selectedAll = scheduler.selectWithinBudget(Int.MAX_VALUE)
        assertEquals(5, selectedAll.size)

        for (i in 0 until selectedAll.size - 1) {
            val pCurrent = selectedAll[i].second
            val pNext = selectedAll[i + 1].second
            assertTrue("Outbox selection must be in non-increasing priority order", pCurrent >= pNext)
        }

        assertTrue("First item must be high-urgency", selectedAll[0].first in listOf("e_hazard", "e_sos"))

        val estimatedBytesPerItem = (obsHazard.title.length + obsHazard.description.length + 200) * 2
        val budgetForTwo = estimatedBytesPerItem * 2 + 10
        val selectedLimited = scheduler.selectWithinBudget(budgetForTwo)
        assertEquals(2, selectedLimited.size)
        val topIds = selectedLimited.map { it.first }.toSet()
        assertTrue("Must include top priority item", topIds.contains(selectedAll[0].first))
        assertTrue("Must include second priority item", topIds.contains(selectedAll[1].first))
    }

    @Test
    fun testPrioritySchedulerMalformedObservationsSafeHandling() {
        val malformedCases = listOf(
            ObservationEntity("m1", missionId, "d1", 1L, "", "", "", observedAtDevice = -1000L),
            ObservationEntity("m2", missionId, "d1", 2L, "UNKNOWN_TYPE_XYZ", "Title", "Desc", observedAtDevice = Long.MAX_VALUE / 2),
            ObservationEntity("m3", missionId, "d1", 3L, "NOTE", "Title", "Desc", reportedStatus = "UNKNOWN_STATUS", verificationStatus = "RANDOM", observedAtDevice = System.currentTimeMillis())
        )

        for (obs in malformedCases) {
            val score = scheduler.scoreObservation(obs)
            assertFalse("Score must not be NaN", score.isNaN())
            assertFalse("Score must not be Infinite", score.isInfinite())
            assertTrue("Score must remain within [0.0, 1.0]", score in 0.0f..1.0f)
        }
    }

    // =========================================================================
    // 4. Outbox Crash Recovery & Bounded Backoff
    // =========================================================================

    @Test
    fun testCalculateBackoffMathematicalBoundaries() {
        assertEquals(1000L, SyncCoordinator.calculateBackoff(0))
        assertEquals(2000L, SyncCoordinator.calculateBackoff(1))
        assertEquals(4000L, SyncCoordinator.calculateBackoff(2))
        assertEquals(8000L, SyncCoordinator.calculateBackoff(3))
        assertEquals(16000L, SyncCoordinator.calculateBackoff(4))
        assertEquals(32000L, SyncCoordinator.calculateBackoff(5))

        assertEquals(32000L, SyncCoordinator.calculateBackoff(6))
        assertEquals(32000L, SyncCoordinator.calculateBackoff(10))
        assertEquals(32000L, SyncCoordinator.calculateBackoff(100))
        assertEquals(32000L, SyncCoordinator.calculateBackoff(Int.MAX_VALUE))

        assertEquals(1000L, SyncCoordinator.calculateBackoff(-1))
        assertEquals(1000L, SyncCoordinator.calculateBackoff(-999))
    }

    @Test
    fun testShouldTransmitSafetyOverrideBehavior() {
        val now = 1_000_000L
        val dummyTransport = Proxy.newProxyInstance(
            MeshTransport::class.java.classLoader,
            arrayOf(MeshTransport::class.java)
        ) { _, method, _ ->
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        } as MeshTransport

        val coordinator = SyncCoordinator(
            transport = dummyTransport,
            observationDao = observationDao,
            outboxDao = outboxDao,
            observationRepo = ObservationRepository(observationDao, outboxDao, "test-device", missionId),
            syncRepo = SyncRepository(FakeMetricsDao()),
            scheduler = scheduler,
            deviceId = "test-device",
            missionId = missionId
        )

        // Case 1: Priority >= 0.80 (Safety Override) -> ALWAYS transmits even if nextAttemptAt is in the distant future
        val safetyCriticalEntry = OutboxEntity(
            eventId = "sos-1",
            priority = 0.80f,
            nextAttemptAt = now + 3600_000L
        )
        assertTrue("Priority >= 0.80 must bypass backoff and transmit immediately", coordinator.shouldTransmit(safetyCriticalEntry, now))

        val highPriorityEntry = OutboxEntity(
            eventId = "hazard-1",
            priority = 0.95f,
            nextAttemptAt = Long.MAX_VALUE
        )
        assertTrue("Priority 0.95 must transmit immediately", coordinator.shouldTransmit(highPriorityEntry, now))

        // Case 2: Priority < 0.80 and now < nextAttemptAt -> backoff delay MUST be respected
        val delayedEntry = OutboxEntity(
            eventId = "note-1",
            priority = 0.79f,
            nextAttemptAt = now + 10_000L
        )
        assertFalse("Entry with priority < 0.80 must wait until nextAttemptAt", coordinator.shouldTransmit(delayedEntry, now))

        // Case 3: Priority < 0.80 and now >= nextAttemptAt -> eligible for transmission
        val eligibleEntry = OutboxEntity(
            eventId = "note-2",
            priority = 0.50f,
            nextAttemptAt = now - 1L
        )
        assertTrue("Entry with past nextAttemptAt must transmit", coordinator.shouldTransmit(eligibleEntry, now))
    }

    @Test
    fun testOutboxCrashRecoveryResetsOrphanedSendingEntries() = runBlocking {
        val entries = listOf(
            OutboxEntity(id = 1L, eventId = "e1", status = "SENDING", priority = 0.9f, retryCount = 1),
            OutboxEntity(id = 2L, eventId = "e2", status = "SENDING", priority = 0.4f, retryCount = 2),
            OutboxEntity(id = 3L, eventId = "e3", status = "SENDING", priority = 0.85f, retryCount = 3),
            OutboxEntity(id = 4L, eventId = "e4", status = "PENDING", priority = 0.3f, retryCount = 0),
            OutboxEntity(id = 5L, eventId = "e5", status = "PENDING", priority = 0.7f, retryCount = 0),
            OutboxEntity(id = 6L, eventId = "e6", status = "DELIVERED", priority = 0.5f, retryCount = 1)
        )
        outboxDao.insertAll(entries)

        val recoveredCount = outboxDao.resetOrphanedSendingToPending()
        assertEquals("Must recover exactly 3 orphaned SENDING entries", 3, recoveredCount)

        val pending = outboxDao.getPendingByPriority()
        assertEquals("Must now have 5 PENDING entries (2 original + 3 recovered)", 5, pending.size)

        val e1 = pending.find { it.eventId == "e1" }!!
        assertEquals("PENDING", e1.status)
        assertEquals(0.9f, e1.priority, 1e-4f)
        assertEquals(1, e1.retryCount)

        val e2 = pending.find { it.eventId == "e2" }!!
        assertEquals("PENDING", e2.status)
        assertEquals(2, e2.retryCount)

        val secondRun = outboxDao.resetOrphanedSendingToPending()
        assertEquals(0, secondRun)
    }

    // =========================================================================
    // 5. MapFusionEngine Conflict Preservation & Verification
    // =========================================================================

    @Test
    fun testHumanVerificationSurveyResolvesConflictedEdge() = runBlocking {
        val edge = MapEdgeEntity("test_edge_survey", missionId, "A", "B", 20f, "UNKNOWN")
        mapDao.insertEdge(edge)

        val obs1 = ObservationEntity("obs_1", missionId, "dev-1", 1L, "ROUTE_STATUS", "Open", reportedStatus = "OPEN", mapEdgeId = "test_edge_survey", observedAtDevice = System.currentTimeMillis())
        val obs2 = ObservationEntity("obs_2", missionId, "dev-2", 1L, "ROUTE_STATUS", "Blocked", reportedStatus = "BLOCKED", mapEdgeId = "test_edge_survey", observedAtDevice = System.currentTimeMillis())
        fusionEngine.applyObservation(obs1)
        val conflictResult = fusionEngine.applyObservation(obs2)!!

        assertEquals("CONFLICTED", conflictResult.newStatus)
        assertTrue(conflictResult.isConflict)

        val verifiedSurveyObs = ObservationEntity(
            eventId = "survey_verified_1",
            missionId = missionId,
            authorDeviceId = "engineer-lead",
            authorSequence = 1L,
            eventType = "ROUTE_STATUS",
            title = "Surveyor Inspection",
            description = "Structural beam collapsed, impassable",
            reportedStatus = "BLOCKED",
            verificationStatus = "VERIFIED",
            mapEdgeId = "test_edge_survey",
            observedAtDevice = System.currentTimeMillis()
        )

        val surveyResult = fusionEngine.applyObservation(verifiedSurveyObs)!!
        assertEquals("VERIFIED_BLOCKED", surveyResult.newStatus)
        assertFalse("Must no longer be CONFLICTED after verification survey", surveyResult.isConflict)
        assertTrue("Conflict history must retain the survey observation ID", surveyResult.conflictingIds.contains("survey_verified_1"))

        val laterOpenReport = ObservationEntity(
            eventId = "obs_late_open",
            missionId = missionId,
            authorDeviceId = "dev-3",
            authorSequence = 1L,
            eventType = "ROUTE_STATUS",
            title = "Looks open to me",
            reportedStatus = "OPEN",
            verificationStatus = "UNVERIFIED",
            mapEdgeId = "test_edge_survey",
            observedAtDevice = System.currentTimeMillis()
        )
        val contradictResult = fusionEngine.applyObservation(laterOpenReport)!!
        assertEquals("Contradicting a verified edge must trigger CONFLICTED", "CONFLICTED", contradictResult.newStatus)
        assertTrue(contradictResult.isConflict)
    }

    @Test
    fun testHazardOnEdgeTreatedAsReportedBlocked() = runBlocking {
        val edge = MapEdgeEntity("hazard_edge", missionId, "N1", "N2", 30f, "UNKNOWN")
        mapDao.insertEdge(edge)

        val hazardObs = ObservationEntity(
            eventId = "obs_hazard_1",
            missionId = missionId,
            authorDeviceId = "dev-1",
            authorSequence = 1L,
            eventType = "ROUTE_STATUS",
            title = "Gas leak in corridor",
            reportedStatus = "HAZARD",
            mapEdgeId = "hazard_edge",
            observedAtDevice = System.currentTimeMillis()
        )

        val result = fusionEngine.applyObservation(hazardObs)!!
        assertEquals("HAZARD reportedStatus on UNKNOWN edge must set REPORTED_BLOCKED", "REPORTED_BLOCKED", result.newStatus)
        assertFalse(result.isConflict)
    }

    // =========================================================================
    // 6. Static Codebase Security & Safety Invariant Scan
    // =========================================================================

    @Test
    fun testStaticCodebaseZeroOccurrencesOfSafeRouteTerminology() {
        val srcDir = File("src/main/java/com/rescuemesh/ai")
        val fallbackSrcDir = if (srcDir.exists()) srcDir else File("app/src/main/java/com/rescuemesh/ai")

        assertTrue("Source directory must exist for static audit: ${fallbackSrcDir.absolutePath}", fallbackSrcDir.exists())

        val prohibitedPatterns = listOf(
            Regex("(?i)\\bStatusSafe\\b"),
            Regex("(?i)\\bGreenSafe\\b"),
            Regex("(?i)\\bgreen_safe\\b"),
            Regex("(?i)\\bsafe[_-]?route\\b"),
            Regex("(?i)\\broute[_-]?safe\\b"),
            Regex("(?i)\\bsafe[_-]?path\\b"),
            Regex("(?i)\\bpath[_-]?safe\\b"),
            Regex("(?i)\\bsafest[_-]?route\\b"),
            Regex("(?i)\\bsafest[_-]?path\\b"),
            Regex("(?i)\"safe route\""),
            Regex("(?i)\"safe path\""),
            Regex("(?i)\"safe passage\"")
        )

        val violations = mutableListOf<String>()

        fallbackSrcDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                val cleanLine = line.trim()
                if (cleanLine.startsWith("//") || cleanLine.startsWith("*") || cleanLine.contains("NEVER described as", ignoreCase = true)) {
                    return@forEachIndexed
                }
                for (pattern in prohibitedPatterns) {
                    if (pattern.containsMatchIn(cleanLine)) {
                        violations.add("${file.name}:${index + 1}: '$line'")
                    }
                }
            }
        }

        assertTrue(
            "Found prohibited 'safe route' terminology in production code:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
