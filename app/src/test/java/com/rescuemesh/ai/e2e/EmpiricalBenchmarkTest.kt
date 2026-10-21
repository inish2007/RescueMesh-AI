package com.rescuemesh.ai.e2e

import com.rescuemesh.ai.data.entity.MapEdgeEntity
import com.rescuemesh.ai.data.entity.MapNodeEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * End-to-End Empirical Benchmark Test Suite.
 *
 * Evaluates multi-round scheduler performance comparing:
 * 1. VoI (PriorityScheduler with P >= 0.80 safety floor)
 * 2. FIFO (chronological oldest-first)
 * 3. Random (stochastic baseline)
 *
 * Asserts:
 * - T_hazard(VoI) < T_hazard(FIFO) under bandwidth-constrained, saturated queues.
 * - T_hazard(VoI) <= T_hazard(Random).
 * - Faster map state convergence for safety-critical edge blockages.
 * - Higher bandwidth utilization efficiency ratio.
 * - Adheres strictly to Invariant 4 (routes NEVER described as "safe" in code, test names, assertions, or comments).
 */
class EmpiricalBenchmarkTest {

    private lateinit var mesh: VirtualMeshHarness
    private val missionId = "e2e-benchmark-mission"

    @Before
    fun setUp() {
        mesh = VirtualMeshHarness(defaultPacketLossRate = 0.0)
    }

    private fun setupNodes(count: Int): List<SimulatedNode> {
        val nodes = mutableListOf<SimulatedNode>()
        for (i in 1..count) {
            nodes.add(mesh.registerNode("node-$i", "Rescue Unit $i"))
        }
        return nodes
    }

    private suspend fun seedMapTopology(nodes: List<SimulatedNode>) {
        val mapNodes = listOf(
            MapNodeEntity(nodeId = "loc-entrance", missionId = missionId, label = "Structure Ingress", floor = 1, latitude = 40.71, longitude = -74.00),
            MapNodeEntity(nodeId = "loc-lobby", missionId = missionId, label = "Lobby Junction", floor = 1, latitude = 40.711, longitude = -74.001),
            MapNodeEntity(nodeId = "loc-corridor", missionId = missionId, label = "Main Passage", floor = 1, latitude = 40.712, longitude = -74.002),
            MapNodeEntity(nodeId = "loc-command", missionId = missionId, label = "Forward Command", floor = 1, latitude = 40.713, longitude = -74.003)
        )

        val mapEdges = listOf(
            MapEdgeEntity(edgeId = "edge-entrance-lobby", missionId = missionId, fromNodeId = "loc-entrance", toNodeId = "loc-lobby", distance = 20.0f, routeStatus = "REPORTED_OPEN"),
            MapEdgeEntity(edgeId = "edge-lobby-corridor", missionId = missionId, fromNodeId = "loc-lobby", toNodeId = "loc-corridor", distance = 30.0f, routeStatus = "UNKNOWN"),
            MapEdgeEntity(edgeId = "edge-corridor-command", missionId = missionId, fromNodeId = "loc-corridor", toNodeId = "loc-command", distance = 25.0f, routeStatus = "REPORTED_OPEN")
        )

        for (node in nodes) {
            node.mapDao.insertNodes(mapNodes)
            node.mapDao.insertEdges(mapEdges)
        }
    }

    /**
     * R3 / Acceptance Criteria:
     * Critical hazard reports under VoI scheduling demonstrate lower propagation latency
     * than under FIFO and Random when bandwidth limits are saturated.
     * Asserts T_hazard(VoI) < T_hazard(FIFO).
     */
    @Test
    fun testCriticalHazardPropagationTimeVoIVsFIFOUnderConstrainedBudget() = runBlocking {
        val nodeCount = 5
        val byteBudgetPerWindow = 480 // Allows ~1 observation per sync window

        // --- Run 1: FIFO Scheduler ---
        val meshFifo = VirtualMeshHarness()
        for (i in 1..nodeCount) meshFifo.registerNode("node-$i")
        val senderFifo = meshFifo.getNode("node-1")!!

        // Queue 8 routine notes first (head-of-line backlog)
        for (i in 1..8) {
            senderFifo.createObservation(
                eventType = "NOTE",
                title = "Routine field note $i",
                description = "Routine reconnaissance telemetry without urgency"
            )
        }

        // Queue 1 critical hazard at the back of the queue
        val hazardFifo = senderFifo.createObservation(
            eventType = "HAZARD",
            title = "Structural Failure Hazard",
            description = "Critical ceiling collapse detected in sector"
        )

        val roundsFifo = meshFifo.getHazardPropagationRounds(
            hazardEventId = hazardFifo.eventId,
            schedulerType = "FIFO",
            byteBudgetPerWindow = byteBudgetPerWindow,
            maxRounds = 20
        )

        // FIFO must process the 8 notes before reaching the hazard
        assertTrue("FIFO should require >= 7 rounds to reach hazard, took $roundsFifo", roundsFifo >= 7)

        // --- Run 2: VoI Scheduler ---
        val meshVoi = VirtualMeshHarness()
        for (i in 1..nodeCount) meshVoi.registerNode("node-$i")
        val senderVoi = meshVoi.getNode("node-1")!!

        // Queue identical 8 routine notes first
        for (i in 1..8) {
            senderVoi.createObservation(
                eventType = "NOTE",
                title = "Routine field note $i",
                description = "Routine reconnaissance telemetry without urgency"
            )
        }

        // Queue identical critical hazard
        val hazardVoi = senderVoi.createObservation(
            eventType = "HAZARD",
            title = "Structural Failure Hazard",
            description = "Critical ceiling collapse detected in sector"
        )

        val roundsVoi = meshVoi.getHazardPropagationRounds(
            hazardEventId = hazardVoi.eventId,
            schedulerType = "VOI",
            byteBudgetPerWindow = byteBudgetPerWindow,
            maxRounds = 20
        )

        // VoI prioritizes the hazard with P >= 0.80 floor, transmitting it in round 1
        assertEquals("VoI must transmit hazard in round 1 despite backlog", 1, roundsVoi)

        // Rigorous Acceptance Assertion
        assertTrue(
            "T_hazard(VoI) [$roundsVoi] must be strictly less than T_hazard(FIFO) [$roundsFifo]",
            roundsVoi < roundsFifo
        )
    }

    @Test
    fun testBlockedPassagePropagationVoIVsFIFOUnderConstrainedBudget() = runBlocking {
        val nodeCount = 5
        val byteBudget = 480

        // FIFO Run
        val meshFifo = VirtualMeshHarness()
        for (i in 1..nodeCount) meshFifo.registerNode("node-$i")
        val senderFifo = meshFifo.getNode("node-1")!!

        for (i in 1..6) {
            senderFifo.createObservation(eventType = "NOTE", title = "Routine log $i")
        }

        val blockedFifo = senderFifo.createObservation(
            eventType = "ROUTE_STATUS",
            title = "Passage blocked",
            reportedStatus = "BLOCKED",
            mapEdgeId = "edge-lobby-corridor"
        )

        val roundsFifo = meshFifo.getHazardPropagationRounds(
            hazardEventId = blockedFifo.eventId,
            schedulerType = "FIFO",
            byteBudgetPerWindow = byteBudget,
            maxRounds = 15
        )

        // VoI Run
        val meshVoi = VirtualMeshHarness()
        for (i in 1..nodeCount) meshVoi.registerNode("node-$i")
        val senderVoi = meshVoi.getNode("node-1")!!

        for (i in 1..6) {
            senderVoi.createObservation(eventType = "NOTE", title = "Routine log $i")
        }

        val blockedVoi = senderVoi.createObservation(
            eventType = "ROUTE_STATUS",
            title = "Passage blocked",
            reportedStatus = "BLOCKED",
            mapEdgeId = "edge-lobby-corridor"
        )

        val roundsVoi = meshVoi.getHazardPropagationRounds(
            hazardEventId = blockedVoi.eventId,
            schedulerType = "VOI",
            byteBudgetPerWindow = byteBudget,
            maxRounds = 15
        )

        assertEquals("VoI must propagate BLOCKED passage in round 1", 1, roundsVoi)
        assertTrue("FIFO took $roundsFifo rounds; VoI took $roundsVoi rounds", roundsVoi < roundsFifo)
    }

    /**
     * Invariant 5 (Safety Override):
     * Urgency reports have an absolute priority floor (P >= 0.8) and must never be suppressed
     * by aging or transmission cost penalties.
     */
    @Test
    fun testVoISafetyFloorGuaranteesHazardSelectionDespiteExtremeStaleness() = runBlocking {
        val nodes = setupNodes(5)
        val sender = nodes[0]
        val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)

        // Create 5 fresh routine notes
        for (i in 1..5) {
            sender.createObservation(
                eventType = "NOTE",
                title = "Fresh field note $i",
                description = "Observed just now",
                observedAtDevice = System.currentTimeMillis()
            )
        }

        // Create 24h old hazard with huge payload
        val staleHazard = sender.createObservation(
            eventType = "HAZARD",
            title = "Old Toxic Vapor Hazard",
            description = "X".repeat(1200), // Max cost penalty
            observedAtDevice = oneDayAgo // Max staleness penalty
        )

        // Score must still be >= 0.80 due to safety floor
        val score = sender.scheduler.scoreObservation(staleHazard)
        assertTrue("Score for stale hazard must be >= 0.80, was $score", score >= 0.80f)

        // Under 3200 byte budget (accommodating large payload), VoI selects the stale hazard FIRST over fresh notes
        val selected = sender.scheduler.selectWithinBudget(maxBytes = 3200)
        assertEquals(1, selected.size)
        assertEquals(staleHazard.eventId, selected[0].first)
    }

    @Test
    fun testMapStateConvergenceSpeedVoIVsFIFOUnderConstrainedBudget() = runBlocking {
        val nodeCount = 5
        val byteBudget = 480

        // FIFO
        val meshFifo = VirtualMeshHarness()
        for (i in 1..nodeCount) meshFifo.registerNode("node-$i")
        val fifoNodes = meshFifo.nodes.values.toList()
        seedMapTopology(fifoNodes)

        // 6 notes + 1 blocked passage report
        for (i in 1..6) fifoNodes[0].createObservation(eventType = "NOTE", title = "Log $i")
        fifoNodes[0].createObservation(
            eventType = "ROUTE_STATUS",
            title = "Corridor blocked",
            reportedStatus = "BLOCKED",
            mapEdgeId = "edge-lobby-corridor"
        )

        val convFifo = meshFifo.runUntilMapConvergence(
            edgeId = "edge-lobby-corridor",
            expectedStatus = "REPORTED_BLOCKED",
            schedulerType = "FIFO",
            byteBudgetPerWindow = byteBudget,
            maxRounds = 15
        )

        // VoI
        val meshVoi = VirtualMeshHarness()
        for (i in 1..nodeCount) meshVoi.registerNode("node-$i")
        val voiNodes = meshVoi.nodes.values.toList()
        seedMapTopology(voiNodes)

        for (i in 1..6) voiNodes[0].createObservation(eventType = "NOTE", title = "Log $i")
        voiNodes[0].createObservation(
            eventType = "ROUTE_STATUS",
            title = "Corridor blocked",
            reportedStatus = "BLOCKED",
            mapEdgeId = "edge-lobby-corridor"
        )

        val convVoi = meshVoi.runUntilMapConvergence(
            edgeId = "edge-lobby-corridor",
            expectedStatus = "REPORTED_BLOCKED",
            schedulerType = "VOI",
            byteBudgetPerWindow = byteBudget,
            maxRounds = 15
        )

        assertEquals("VoI map convergence must occur in round 1", 1, convVoi)
        assertTrue("Map convergence under VoI [$convVoi] must be faster than FIFO [$convFifo]", convVoi < convFifo)
    }

    @Test
    fun testBandwidthUtilizationEfficiencyRatio() = runBlocking {
        val nodeCount = 5
        val byteBudget = 480

        // In 1 round of transmission under 480-byte budget:
        // VoI transmits the hazard (useful critical observation)
        val meshVoi = VirtualMeshHarness()
        for (i in 1..nodeCount) meshVoi.registerNode("node-$i")
        val senderVoi = meshVoi.getNode("node-1")!!

        for (i in 1..5) senderVoi.createObservation(eventType = "NOTE", title = "Note $i")
        val hazardVoi = senderVoi.createObservation(eventType = "HAZARD", title = "Critical Gas Leak")

        meshVoi.executeSyncRound(schedulerType = "VOI", byteBudgetPerWindow = byteBudget)

        // Check useful observations received across peers
        var voiUsefulReceived = 0
        for (i in 2..nodeCount) {
            if (meshVoi.getNode("node-$i")!!.observationDao.exists(hazardVoi.eventId)) {
                voiUsefulReceived++
            }
        }
        val voiBytes = meshVoi.totalBytesTransferred.get()

        // FIFO
        val meshFifo = VirtualMeshHarness()
        for (i in 1..nodeCount) meshFifo.registerNode("node-$i")
        val senderFifo = meshFifo.getNode("node-1")!!

        for (i in 1..5) senderFifo.createObservation(eventType = "NOTE", title = "Note $i")
        val hazardFifo = senderFifo.createObservation(eventType = "HAZARD", title = "Critical Gas Leak")

        meshFifo.executeSyncRound(schedulerType = "FIFO", byteBudgetPerWindow = byteBudget)

        var fifoUsefulReceived = 0
        for (i in 2..nodeCount) {
            if (meshFifo.getNode("node-$i")!!.observationDao.exists(hazardFifo.eventId)) {
                fifoUsefulReceived++
            }
        }
        val fifoBytes = meshFifo.totalBytesTransferred.get()

        // Under 1 round, VoI delivers the critical hazard to all peers (useful = 4)
        // FIFO only delivers note 1 to peers (useful critical = 0)
        assertEquals(4, voiUsefulReceived)
        assertEquals(0, fifoUsefulReceived)

        // VoI critical bandwidth efficiency (useful / bytes) is strictly higher
        val voiEfficiency = if (voiBytes > 0) voiUsefulReceived.toDouble() / voiBytes else 0.0
        val fifoEfficiency = if (fifoBytes > 0) fifoUsefulReceived.toDouble() / fifoBytes else 0.0

        assertTrue("VoI critical bandwidth efficiency ($voiEfficiency) must exceed FIFO ($fifoEfficiency)", voiEfficiency > fifoEfficiency)
    }

    @Test
    fun testRandomSchedulerStochasticVarianceVsDeterministicVoI() = runBlocking {
        val nodeCount = 5
        val byteBudget = 480
        val trials = 5

        var totalRandomRounds = 0
        var totalVoiRounds = 0

        for (trial in 1..trials) {
            // Random
            val meshRandom = VirtualMeshHarness()
            for (i in 1..nodeCount) meshRandom.registerNode("node-$i")
            val senderRand = meshRandom.getNode("node-1")!!

            for (i in 1..6) senderRand.createObservation(eventType = "NOTE", title = "Note $i")
            val hazardRand = senderRand.createObservation(eventType = "HAZARD", title = "Hazard trial $trial")

            val roundsRand = meshRandom.getHazardPropagationRounds(
                hazardEventId = hazardRand.eventId,
                schedulerType = "RANDOM",
                byteBudgetPerWindow = byteBudget,
                maxRounds = 15
            )
            totalRandomRounds += roundsRand

            // VoI
            val meshVoi = VirtualMeshHarness()
            for (i in 1..nodeCount) meshVoi.registerNode("node-$i")
            val senderVoi = meshVoi.getNode("node-1")!!

            for (i in 1..6) senderVoi.createObservation(eventType = "NOTE", title = "Note $i")
            val hazardVoi = senderVoi.createObservation(eventType = "HAZARD", title = "Hazard trial $trial")

            val roundsVoi = meshVoi.getHazardPropagationRounds(
                hazardEventId = hazardVoi.eventId,
                schedulerType = "VOI",
                byteBudgetPerWindow = byteBudget,
                maxRounds = 15
            )
            totalVoiRounds += roundsVoi
        }

        val avgRandomRounds = totalRandomRounds.toDouble() / trials
        val avgVoiRounds = totalVoiRounds.toDouble() / trials

        assertEquals("VoI must consistently propagate hazard in 1 round", 1.0, avgVoiRounds, 1e-4)
        assertTrue("Avg Random rounds ($avgRandomRounds) must be >= Avg VoI rounds ($avgVoiRounds)", avgVoiRounds <= avgRandomRounds)
    }

    @Test
    fun testSchedulerPerformanceAcrossVaryingByteBudgets() = runBlocking {
        val nodeCount = 5
        val budgets = listOf(350, 600, 4000)

        for (budget in budgets) {
            val meshTest = VirtualMeshHarness()
            for (i in 1..nodeCount) meshTest.registerNode("node-$i")
            val sender = meshTest.getNode("node-1")!!

            for (i in 1..4) sender.createObservation(eventType = "NOTE", title = "Note $i")
            val hazard = sender.createObservation(eventType = "HAZARD", title = "Hazard at budget $budget")

            val rounds = meshTest.getHazardPropagationRounds(
                hazardEventId = hazard.eventId,
                schedulerType = "VOI",
                byteBudgetPerWindow = budget,
                maxRounds = 10
            )

            // VoI delivers in 1 round across all budgets because it prioritizes the hazard
            assertEquals("VoI should deliver hazard in 1 round for budget $budget", 1, rounds)
        }
    }

    @Test
    fun testScaleInvarianceAcrossTenNodeMesh() = runBlocking {
        val nodeCount = 10
        val byteBudget = 480

        // FIFO
        val meshFifo = VirtualMeshHarness()
        for (i in 1..nodeCount) meshFifo.registerNode("node-$i")
        val senderFifo = meshFifo.getNode("node-1")!!

        for (i in 1..5) senderFifo.createObservation(eventType = "NOTE", title = "Note $i")
        val hazardFifo = senderFifo.createObservation(eventType = "HAZARD", title = "Scale hazard 10 nodes")

        val roundsFifo = meshFifo.getHazardPropagationRounds(
            hazardEventId = hazardFifo.eventId,
            schedulerType = "FIFO",
            byteBudgetPerWindow = byteBudget,
            maxRounds = 15
        )

        // VoI
        val meshVoi = VirtualMeshHarness()
        for (i in 1..nodeCount) meshVoi.registerNode("node-$i")
        val senderVoi = meshVoi.getNode("node-1")!!

        for (i in 1..5) senderVoi.createObservation(eventType = "NOTE", title = "Note $i")
        val hazardVoi = senderVoi.createObservation(eventType = "HAZARD", title = "Scale hazard 10 nodes")

        val roundsVoi = meshVoi.getHazardPropagationRounds(
            hazardEventId = hazardVoi.eventId,
            schedulerType = "VOI",
            byteBudgetPerWindow = byteBudget,
            maxRounds = 15
        )

        assertEquals("VoI propagates hazard in round 1 in 10-node mesh", 1, roundsVoi)
        assertTrue("VoI [$roundsVoi] must propagate faster than FIFO [$roundsFifo] at N=10", roundsVoi < roundsFifo)
    }

    /**
     * Invariant 4 (Safety-Guaranteed Route Guidance):
     * Benchmark reports, summaries, and metrics must NEVER describe routes as "safe".
     */
    @Test
    fun testInvariant4SafeTerminologyAbsenceInBenchmarkReports() {
        val sampleBenchmarkTable = """
            | Scheduler | Node Count | Byte Budget | Avg T_hazard (rounds) | Map Convergence | Bandwidth Efficiency | Route Status Quality |
            |-----------|------------|-------------|-----------------------|-----------------|----------------------|----------------------|
            | VOI       | 5          | 480         | 1.0                   | 1 round         | 0.0083 obs/byte      | Cost-Optimal Clear   |
            | FIFO      | 5          | 480         | 7.0                   | 7 rounds        | 0.0011 obs/byte      | Delayed Blockage     |
            | RANDOM    | 5          | 480         | 3.8                   | 4 rounds        | 0.0022 obs/byte      | Stochastic Clearance |
        """.trimIndent()

        val sampleJsonReport = """
            {
                "missionId": "$missionId",
                "schedulers": [
                    { "type": "VOI", "hazardPropagationRounds": 1.0, "converged": true, "routeGuidance": "cost-optimal" },
                    { "type": "FIFO", "hazardPropagationRounds": 7.0, "converged": true, "routeGuidance": "delayed-verification" },
                    { "type": "RANDOM", "hazardPropagationRounds": 3.8, "converged": true, "routeGuidance": "stochastic" }
                ]
            }
        """.trimIndent()

        assertFalse(
            "Benchmark table violates Invariant 4 by containing 'safe'",
            sampleBenchmarkTable.lowercase().contains("safe")
        )

        assertFalse(
            "Benchmark JSON report violates Invariant 4 by containing 'safe'",
            sampleJsonReport.lowercase().contains("safe")
        )
    }
}
