package com.rescuemesh.ai.intelligence

import com.rescuemesh.ai.data.entity.MapEdgeEntity
import com.rescuemesh.ai.data.entity.MapNodeEntity
import com.rescuemesh.ai.fakes.FakeMapDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method

class RoutePlannerTest {

    private lateinit var mapDao: FakeMapDao
    private lateinit var routePlanner: RoutePlanner

    private val missionId = "alpha-mission"

    @Before
    fun setUp() {
        mapDao = FakeMapDao()
        routePlanner = RoutePlanner(mapDao)
    }

    private fun addNode(nodeId: String, label: String) {
        val node = MapNodeEntity(
            nodeId = nodeId,
            missionId = missionId,
            label = label,
            floor = 1,
            latitude = 40.7128,
            longitude = -74.0060
        )
        runBlocking { mapDao.insertNode(node) }
    }

    private fun addEdge(
        edgeId: String,
        fromNodeId: String,
        toNodeId: String,
        distance: Float,
        routeStatus: String = "REPORTED_OPEN"
    ) {
        val edge = MapEdgeEntity(
            edgeId = edgeId,
            missionId = missionId,
            fromNodeId = fromNodeId,
            toNodeId = toNodeId,
            distance = distance,
            routeStatus = routeStatus
        )
        runBlocking { mapDao.insertEdge(edge) }
    }

    @Test
    fun testDijkstraPathfindingBetweenNodes() = runBlocking {
        addNode("A", "Entrance")
        addNode("B", "Lobby")
        addNode("C", "Exit")

        // Direct long edge vs two short edges
        addEdge("e-AB", "A", "B", 10.0f, "VERIFIED_OPEN")
        addEdge("e-BC", "B", "C", 10.0f, "VERIFIED_OPEN")
        addEdge("e-AC", "A", "C", 35.0f, "VERIFIED_OPEN")

        val result = routePlanner.findRoute(fromNodeId = "A", toNodeId = "C", missionId = missionId)

        assertTrue(result.isComplete)
        assertEquals(listOf("A", "B", "C"), result.path)
        assertEquals(listOf("Entrance", "Lobby", "Exit"), result.pathLabels)
        assertEquals(20.0, result.totalDistance, 1e-4)
        assertEquals(20.0, result.totalCost, 1e-4)
        assertEquals(listOf("e-AB", "e-BC"), result.edgesUsed)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun testExclusionOfReportedBlockedEdgesWhenAvoidBlockedIsTrue() = runBlocking {
        addNode("A", "Entrance")
        addNode("B", "Corridor")
        addNode("C", "Command Post")

        // Direct path A -> B -> C, but A -> B is REPORTED_BLOCKED
        addEdge("e-AB", "A", "B", 10.0f, "REPORTED_BLOCKED")
        addEdge("e-BC", "B", "C", 10.0f, "REPORTED_OPEN")
        // Alternate path A -> C
        addEdge("e-AC", "A", "C", 40.0f, "REPORTED_OPEN")

        // Default avoidBlocked = true
        val result = routePlanner.findRoute(fromNodeId = "A", toNodeId = "C", missionId = missionId, avoidBlocked = true)

        assertTrue(result.isComplete)
        assertEquals(listOf("A", "C"), result.path)
        assertEquals(listOf("e-AC"), result.edgesUsed)
        assertEquals(40.0, result.totalDistance, 1e-4)

        // When avoidBlocked is false, blocked edge can be traversed with BLOCKED_COST penalty
        val blockedAllowedResult = routePlanner.findRoute(
            fromNodeId = "A",
            toNodeId = "C",
            missionId = missionId,
            avoidBlocked = false
        )
        assertTrue(blockedAllowedResult.isComplete)
        // Alternate open path is much cheaper than BLOCKED_COST (which is Double.MAX_VALUE / 2)
        assertEquals(listOf("A", "C"), blockedAllowedResult.path)
    }

    @Test
    fun testExclusionOfVerifiedBlockedEdges() = runBlocking {
        addNode("A", "Entrance")
        addNode("B", "Exit")

        addEdge("e-AB", "A", "B", 15.0f, "VERIFIED_BLOCKED")

        val result = routePlanner.findRoute(fromNodeId = "A", toNodeId = "B", missionId = missionId, avoidBlocked = true)

        assertFalse(result.isComplete)
        assertTrue(result.path.isEmpty())
        assertTrue(result.warnings.any { it.contains("No route found") })
    }

    @Test
    fun testRiskPenaltyAppliedToConflictedEdges() = runBlocking {
        addNode("A", "Start")
        addNode("B", "Destination")
        addNode("C", "Detour")

        // Direct edge has distance 10.0, but is CONFLICTED
        addEdge("e-AB", "A", "B", 10.0f, "CONFLICTED")
        // Verified detour isolates the conflict penalty from uncertainty costs.
        addEdge("e-AC", "A", "C", 25.0f, "VERIFIED_OPEN")
        addEdge("e-CB", "C", "B", 25.0f, "VERIFIED_OPEN")

        // With default riskPenalty = 100.0, e-AB cost is 10 + 100 = 110.0.
        // Detour cost is 25 + 25 = 50.0. RoutePlanner should pick detour!
        val detourResult = routePlanner.findRoute(
            fromNodeId = "A",
            toNodeId = "B",
            missionId = missionId,
            riskPenalty = RoutePlanner.DEFAULT_RISK_PENALTY
        )
        assertTrue(detourResult.isComplete)
        assertEquals(listOf("A", "C", "B"), detourResult.path)
        assertEquals(50.0, detourResult.totalCost, 1e-4)

        // With low riskPenalty = 5.0, e-AB cost is 10 + 5 = 15.0 < 50.0.
        // RoutePlanner should pick direct CONFLICTED edge!
        val directResult = routePlanner.findRoute(
            fromNodeId = "A",
            toNodeId = "B",
            missionId = missionId,
            riskPenalty = 5.0
        )
        assertTrue(directResult.isComplete)
        assertEquals(listOf("A", "B"), directResult.path)
        assertEquals(15.0, directResult.totalCost, 1e-4)
        // Warning flag must be added for the conflicted edge
        assertTrue(directResult.warnings.any { it.contains("CONFLICTED status") })
    }

    @Test
    fun testUncertaintyPenaltyAppliedToUnknownEdges() = runBlocking {
        addNode("A", "Start")
        addNode("B", "Goal")
        addNode("C", "Surveyed Corridor")

        // Direct edge has distance 10.0, but status is UNKNOWN
        addEdge("e-AB", "A", "B", 10.0f, "UNKNOWN")
        // Detour via C has distance 35.0 and is verified.
        addEdge("e-AC", "A", "C", 20.0f, "VERIFIED_OPEN")
        addEdge("e-CB", "C", "B", 15.0f, "VERIFIED_OPEN")

        // With default uncertainty penalty = 50.0, e-AB cost is 10 + 50 = 60.0 > 35.0
        val detourResult = routePlanner.findRoute(
            fromNodeId = "A",
            toNodeId = "B",
            missionId = missionId,
            uncertaintyPenalty = RoutePlanner.DEFAULT_UNCERTAINTY_PENALTY
        )
        assertTrue(detourResult.isComplete)
        assertEquals(listOf("A", "C", "B"), detourResult.path)
        assertEquals(35.0, detourResult.totalCost, 1e-4)

        // With low uncertainty penalty = 5.0, e-AB cost is 10 + 5 = 15.0 < 35.0
        val unknownResult = routePlanner.findRoute(
            fromNodeId = "A",
            toNodeId = "B",
            missionId = missionId,
            uncertaintyPenalty = 5.0
        )
        assertTrue(unknownResult.isComplete)
        assertEquals(listOf("A", "B"), unknownResult.path)
        assertEquals(15.0, unknownResult.totalCost, 1e-4)
        assertTrue(unknownResult.warnings.any { it.contains("status UNKNOWN") })
    }

    @Test
    fun testReportedOpenCarriesUncertaintyCostAndExplicitWarning() = runBlocking {
        addNode("A", "Start")
        addNode("B", "Goal")
        addEdge("passage", "A", "B", 10.0f, "REPORTED_OPEN")
        val reported = routePlanner.findRoute("A", "B", missionId)
        assertTrue(reported.isComplete)
        assertEquals(10.0, reported.totalDistance, 1e-4)
        assertTrue(reported.warnings.any { it.contains("UNVERIFIED") })

        addEdge("passage", "A", "B", 10.0f, "VERIFIED_OPEN")
        val verified = routePlanner.findRoute("A", "B", missionId)
        assertTrue(reported.totalCost > verified.totalCost)
        assertEquals(10.0, verified.totalCost, 1e-4)
        assertTrue(verified.warnings.isEmpty())
    }

    @Test
    fun testUncertaintyCanFavorLongerVerifiedPassage() = runBlocking {
        addNode("A", "Start")
        addNode("B", "Goal")
        addEdge("reported", "A", "B", 10.0f, "REPORTED_OPEN")
        addEdge("surveyed", "A", "B", 20.0f, "VERIFIED_OPEN")
        val result = routePlanner.findRoute("A", "B", missionId)
        assertEquals(listOf("surveyed"), result.edgesUsed)
        assertEquals(20.0, result.totalDistance, 1e-4)
    }

    @Test
    fun testWarningGenerationFlagsForConflictedUnknownAndBlockedEdges() = runBlocking {
        addNode("N1", "Node 1")
        addNode("N2", "Node 2")
        addNode("N3", "Node 3")
        addNode("N4", "Node 4")

        addEdge("e-12", "N1", "N2", 10.0f, "CONFLICTED")
        addEdge("e-23", "N2", "N3", 10.0f, "UNKNOWN")
        addEdge("e-34", "N3", "N4", 10.0f, "REPORTED_BLOCKED")

        val result = routePlanner.findRoute(
            fromNodeId = "N1",
            toNodeId = "N4",
            missionId = missionId,
            avoidBlocked = false,
            avoidConflicted = false
        )

        assertTrue(result.isComplete)
        assertEquals(3, result.warnings.size)
        assertTrue("Must flag CONFLICTED", result.warnings.any { it.contains("CONFLICTED status") })
        assertTrue("Must flag UNKNOWN", result.warnings.any { it.contains("status UNKNOWN") })
        assertTrue("Must flag BLOCKED", result.warnings.any { it.contains("reported BLOCKED") })
    }

    @Test
    fun testAdvisoryExplorationSuggestions() = runBlocking {
        addNode("N1", "Node 1")
        addNode("N2", "Node 2")
        addNode("N3", "Node 3")
        addNode("N4", "Node 4")

        addEdge("e1", "N1", "N2", 10.0f, "REPORTED_OPEN")
        addEdge("e2", "N2", "N3", 10.0f, "UNKNOWN")
        addEdge("e3", "N3", "N4", 10.0f, "UNKNOWN")

        val suggestions = routePlanner.suggestExploration(missionId)

        // Edges e2 (N2-N3) and e3 (N3-N4) are UNKNOWN
        val expectedNodes = setOf("N2", "N3", "N4")
        assertEquals(expectedNodes, suggestions.toSet())
    }

    @Test
    fun testAdvisoryExplorationEmptyWhenNoUnknownEdges() = runBlocking {
        addNode("N1", "Node 1")
        addNode("N2", "Node 2")
        addEdge("e1", "N1", "N2", 10.0f, "REPORTED_OPEN")

        val suggestions = routePlanner.suggestExploration(missionId)
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun testEmptyMapReturnsIncompleteRoute() = runBlocking {
        val result = routePlanner.findRoute("A", "B", "non-existent-mission")
        assertFalse(result.isComplete)
        assertEquals(listOf("No map data available"), result.warnings)
    }

    /**
     * Invariant 4 (Safety-Guaranteed Route Guidance):
     * Routes must NEVER be described as "safe" in code, logs, or UI.
     */
    @Test
    fun testInvariant4NoSafeDescriptionInRoutePlanner() = runBlocking {
        addNode("A", "Alpha")
        addNode("B", "Bravo")
        addEdge("e-AB", "A", "B", 10.0f, "REPORTED_OPEN")

        val result = routePlanner.findRoute("A", "B", missionId)

        // Check result warnings and labels do not contain the forbidden word "safe"
        for (warning in result.warnings) {
            assertFalse(
                "Warning must never contain 'safe': $warning",
                warning.lowercase().contains("safe")
            )
        }

        // Verify class methods do not contain 'safe'
        val methods: Array<Method> = RoutePlanner::class.java.declaredMethods
        for (method in methods) {
            val name = method.name.lowercase()
            assertFalse(
                "RoutePlanner method '${method.name}' violates Invariant 4 by containing 'safe'",
                name.contains("safe")
            )
        }
    }
}
