package com.rescuemesh.ai.benchmark

import com.rescuemesh.ai.data.entity.MapEdgeEntity
import com.rescuemesh.ai.data.entity.MapNodeEntity
import com.rescuemesh.ai.simulation.SimulatedRescueNode
import com.rescuemesh.ai.simulation.VirtualMeshEnvironment
import kotlinx.coroutines.runBlocking

/**
 * Automated multi-round empirical benchmark runner.
 * Evaluates VoI (with P >= 0.80 safety floor) vs FIFO vs Random baselines under constrained byte budgets.
 *
 * Adheres strictly to:
 * - Invariant 1: Strictly offline-first (zero external network/cloud calls).
 * - Invariant 2: Durable local-first persistence.
 * - Invariant 3: Conflict-preserving map fusion.
 * - Invariant 4: Routes NEVER described as "safe" in code, test names, assertions, or comments.
 * - Invariant 5: Value-of-Information safety floor (P >= 0.80 for HAZARD and BLOCKED routes).
 */
class BenchmarkRunner(
    val config: BenchmarkRunConfig = BenchmarkRunConfig()
) {

    fun runBenchmark(): BenchmarkSuiteResult = runBlocking {
        val metricsMap = mutableMapOf<String, BenchmarkMetrics>()

        for (schedulerType in config.schedulerTypes) {
            var sumHazardRounds = 0.0
            var sumHazardTimeMs = 0.0
            var sumConvergenceRounds = 0.0
            var sumBytes = 0L
            var sumUseful = 0

            for (round in 1..config.rounds) {
                val roundResult = runSingleRound(schedulerType, round)
                sumHazardRounds += roundResult.hazardRounds
                sumHazardTimeMs += roundResult.hazardTimeMs
                sumConvergenceRounds += roundResult.convergenceRounds
                sumBytes += roundResult.bytesTransferred
                sumUseful += roundResult.usefulPropagated
            }

            val avgHazardRounds = sumHazardRounds / config.rounds
            val avgHazardTimeMs = sumHazardTimeMs / config.rounds
            val avgConvergenceRounds = sumConvergenceRounds / config.rounds
            val totalBytes = sumBytes
            val usefulTotal = sumUseful
            val efficiency = if (totalBytes > 0) usefulTotal.toDouble() / totalBytes else 0.0

            metricsMap[schedulerType] = BenchmarkMetrics(
                schedulerType = schedulerType,
                avgHazardPropagationRounds = avgHazardRounds,
                avgHazardPropagationTimeMs = avgHazardTimeMs,
                mapConvergenceRounds = avgConvergenceRounds,
                totalPayloadBytesTransferred = totalBytes,
                usefulObservationsPropagated = usefulTotal,
                bandwidthEfficiencyRatio = efficiency
            )
        }

        BenchmarkSuiteResult(
            config = config,
            metricsByScheduler = metricsMap
        )
    }

    private data class SingleRoundResult(
        val hazardRounds: Int,
        val hazardTimeMs: Double,
        val convergenceRounds: Int,
        val bytesTransferred: Long,
        val usefulPropagated: Int
    )

    private suspend fun runSingleRound(schedulerType: String, roundIndex: Int): SingleRoundResult {
        // --- 1. Critical Hazard Propagation Evaluation ---
        val meshHazard = VirtualMeshEnvironment(packetLossRate = config.packetLossRate)
        val missionIdHazard = "bench-hazard-r$roundIndex"

        val hazardNodes = mutableListOf<SimulatedRescueNode>()
        for (i in 1..config.nodeCount) {
            hazardNodes.add(meshHazard.registerNode("node-$i", "Rescue Node $i", missionIdHazard))
        }

        val senderHazard = hazardNodes[0]

        // Queue routine notes to create head-of-line backlog
        for (i in 1..config.routineNotesCount) {
            senderHazard.createObservation(
                eventType = "NOTE",
                title = "Routine field note $i",
                description = "Routine reconnaissance telemetry without urgency"
            )
        }

        // Queue critical hazard at the back of the queue
        val hazard = senderHazard.createObservation(
            eventType = "HAZARD",
            title = "Critical Structural Hazard",
            description = "Structural collapse detected in passage"
        )

        val startTime = System.currentTimeMillis()
        val hazardRounds = meshHazard.getHazardPropagationRounds(
            hazardEventId = hazard.eventId,
            schedulerType = schedulerType,
            byteBudgetPerWindow = config.byteBudgetPerWindow,
            maxRounds = 20
        )
        val hazardTimeMs = (hazardRounds * 100.0).coerceAtLeast((System.currentTimeMillis() - startTime).toDouble())
        val bytesHazard = meshHazard.totalBytesTransferred.get()

        var usefulHazard = 0
        for (i in 1 until hazardNodes.size) {
            if (hazardNodes[i].observationDao.exists(hazard.eventId)) {
                usefulHazard++
            }
        }

        // --- 2. Map State Convergence Evaluation ---
        val meshMap = VirtualMeshEnvironment(packetLossRate = config.packetLossRate)
        val missionIdMap = "bench-map-r$roundIndex"

        val mapNodesList = mutableListOf<SimulatedRescueNode>()
        for (i in 1..config.nodeCount) {
            mapNodesList.add(meshMap.registerNode("node-$i", "Rescue Node $i", missionIdMap))
        }

        val topologyNodes = listOf(
            MapNodeEntity(nodeId = "loc-base", missionId = missionIdMap, label = "Staging Base", floor = 1, latitude = 40.71, longitude = -74.00),
            MapNodeEntity(nodeId = "loc-corridor", missionId = missionIdMap, label = "West Corridor", floor = 1, latitude = 40.712, longitude = -74.002),
            MapNodeEntity(nodeId = "loc-exit", missionId = missionIdMap, label = "North Exit", floor = 1, latitude = 40.714, longitude = -74.004)
        )
        val edgeId = "edge-corridor-exit"
        val topologyEdges = listOf(
            MapEdgeEntity(edgeId = "edge-base-corridor", missionId = missionIdMap, fromNodeId = "loc-base", toNodeId = "loc-corridor", distance = 25.0f, routeStatus = "REPORTED_OPEN"),
            MapEdgeEntity(edgeId = edgeId, missionId = missionIdMap, fromNodeId = "loc-corridor", toNodeId = "loc-exit", distance = 30.0f, routeStatus = "UNKNOWN")
        )
        for (node in mapNodesList) {
            node.mapDao.insertNodes(topologyNodes)
            node.mapDao.insertEdges(topologyEdges)
        }

        val senderMap = mapNodesList[0]
        for (i in 1..6) {
            senderMap.createObservation(eventType = "NOTE", title = "Log $i")
        }

        val blockedObs = senderMap.createObservation(
            eventType = "ROUTE_STATUS",
            title = "Corridor Passage Blocked",
            reportedStatus = "BLOCKED",
            mapEdgeId = edgeId,
            description = "Passage completely impassable due to debris"
        )

        val convergenceRounds = meshMap.runUntilMapConvergence(
            edgeId = edgeId,
            expectedStatus = "REPORTED_BLOCKED",
            schedulerType = schedulerType,
            byteBudgetPerWindow = config.byteBudgetPerWindow,
            maxRounds = 20
        )
        val bytesMap = meshMap.totalBytesTransferred.get()

        var usefulMap = 0
        for (i in 1 until mapNodesList.size) {
            if (mapNodesList[i].observationDao.exists(blockedObs.eventId)) {
                usefulMap++
            }
        }

        return SingleRoundResult(
            hazardRounds = hazardRounds,
            hazardTimeMs = hazardTimeMs,
            convergenceRounds = convergenceRounds,
            bytesTransferred = bytesHazard + bytesMap,
            usefulPropagated = usefulHazard + usefulMap
        )
    }
}
