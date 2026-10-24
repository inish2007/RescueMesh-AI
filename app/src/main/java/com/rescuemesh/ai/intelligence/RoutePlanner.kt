package com.rescuemesh.ai.intelligence

import com.rescuemesh.ai.data.dao.MapDao
import com.rescuemesh.ai.data.entity.MapEdgeEntity
import com.rescuemesh.ai.data.entity.MapNodeEntity
import java.util.PriorityQueue

/**
 * Graph-based route planner using Dijkstra's algorithm.
 *
 * Operates on the operational map graph with configurable cost function:
 *   Cost(edge) = Distance(e) + λ * RiskPenalty(e) + μ * UncertaintyPenalty(e)
 *
 * Rules:
 * - BLOCKED edges are excluded from routing (unless explicitly allowed)
 * - CONFLICTED edges receive a risk penalty
 * - UNKNOWN/UNVERIFIED edges receive an uncertainty penalty
 * - Routes are NEVER described as "safe" — only as "best available"
 *
 * Performance: O((V+E)log V) with binary heap — more than sufficient for building-scale graphs.
 */
class RoutePlanner(private val mapDao: MapDao) {

    companion object {
        const val DEFAULT_RISK_PENALTY = 100.0
        const val DEFAULT_UNCERTAINTY_PENALTY = 50.0
        const val BLOCKED_COST = Double.MAX_VALUE / 2
    }

    data class RouteResult(
        val path: List<String>,           // Node IDs from start to end
        val pathLabels: List<String>,     // Human-readable labels
        val totalDistance: Double,         // Raw distance in meters
        val totalCost: Double,            // Weighted cost including penalties
        val edgesUsed: List<String>,      // Edge IDs traversed
        val warnings: List<String>,       // Risk/uncertainty warnings
        val isComplete: Boolean           // Whether a path was found
    )

    /**
     * Find the best route between two nodes.
     *
     * @param avoidBlocked Skip edges with REPORTED_BLOCKED or VERIFIED_BLOCKED status
     * @param avoidConflicted Skip edges with CONFLICTED status
     * @param riskPenalty Additional cost for risky edges
     * @param uncertaintyPenalty Additional cost for unknown/unverified edges
     */
    suspend fun findRoute(
        fromNodeId: String,
        toNodeId: String,
        missionId: String,
        avoidBlocked: Boolean = true,
        avoidConflicted: Boolean = false,
        riskPenalty: Double = DEFAULT_RISK_PENALTY,
        uncertaintyPenalty: Double = DEFAULT_UNCERTAINTY_PENALTY
    ): RouteResult {
        val nodes = mapDao.getAllNodes(missionId)
        val edges = mapDao.getAllEdges(missionId)

        if (nodes.isEmpty() || edges.isEmpty()) {
            return RouteResult(emptyList(), emptyList(), 0.0, 0.0, emptyList(), listOf("No map data available"), false)
        }

        // Build adjacency list
        val nodeMap = nodes.associateBy { it.nodeId }
        val adjacency = mutableMapOf<String, MutableList<Pair<MapEdgeEntity, String>>>()

        for (edge in edges) {
            // Skip blocked edges if configured
            if (avoidBlocked && edge.routeStatus in listOf("REPORTED_BLOCKED", "VERIFIED_BLOCKED")) continue
            if (avoidConflicted && edge.routeStatus == "CONFLICTED") continue

            // Bidirectional edges
            adjacency.getOrPut(edge.fromNodeId) { mutableListOf() }.add(edge to edge.toNodeId)
            adjacency.getOrPut(edge.toNodeId) { mutableListOf() }.add(edge to edge.fromNodeId)
        }

        // Dijkstra
        val dist = mutableMapOf<String, Double>()
        val prev = mutableMapOf<String, String>()
        val prevEdge = mutableMapOf<String, String>()
        val visited = mutableSetOf<String>()

        data class QueueEntry(val nodeId: String, val cost: Double) : Comparable<QueueEntry> {
            override fun compareTo(other: QueueEntry) = cost.compareTo(other.cost)
        }

        val pq = PriorityQueue<QueueEntry>()
        dist[fromNodeId] = 0.0
        pq.add(QueueEntry(fromNodeId, 0.0))

        while (pq.isNotEmpty()) {
            val entry = pq.poll() ?: break
            val (currentNode, currentCost) = entry

            if (currentNode in visited) continue
            visited.add(currentNode)

            if (currentNode == toNodeId) break

            val neighbors = adjacency[currentNode] ?: continue
            for ((edge, neighborId) in neighbors) {
                if (neighborId in visited) continue

                val edgeCost = computeEdgeCost(edge, riskPenalty, uncertaintyPenalty)
                val newCost = currentCost + edgeCost

                if (newCost < (dist[neighborId] ?: Double.MAX_VALUE)) {
                    dist[neighborId] = newCost
                    prev[neighborId] = currentNode
                    prevEdge[neighborId] = edge.edgeId
                    pq.add(QueueEntry(neighborId, newCost))
                }
            }
        }

        // Reconstruct path
        if (toNodeId !in dist) {
            return RouteResult(
                emptyList(), emptyList(), 0.0, 0.0, emptyList(),
                listOf("No route found from ${nodeMap[fromNodeId]?.label ?: fromNodeId} to ${nodeMap[toNodeId]?.label ?: toNodeId}"),
                false
            )
        }

        val path = mutableListOf<String>()
        val edgesUsed = mutableListOf<String>()
        var current: String? = toNodeId
        while (current != null) {
            path.add(0, current)
            val edgeId = prevEdge[current]
            if (edgeId != null) edgesUsed.add(0, edgeId)
            current = prev[current]
        }

        val pathLabels = path.map { nodeMap[it]?.label ?: it }

        // Collect warnings
        val warnings = mutableListOf<String>()
        var totalDistance = 0.0
        for (edgeId in edgesUsed) {
            val edge = edges.find { it.edgeId == edgeId } ?: continue
            totalDistance += edge.distance

            when (edge.routeStatus) {
                "CONFLICTED" -> warnings.add("⚠️ ${nodeMap[edge.fromNodeId]?.label} → ${nodeMap[edge.toNodeId]?.label}: CONFLICTED status")
                "UNKNOWN" -> warnings.add("❓ ${nodeMap[edge.fromNodeId]?.label} → ${nodeMap[edge.toNodeId]?.label}: status UNKNOWN")
                "REPORTED_OPEN" -> warnings.add("⚠️ ${nodeMap[edge.fromNodeId]?.label} → ${nodeMap[edge.toNodeId]?.label}: UNVERIFIED passage")
                "REPORTED_BLOCKED", "VERIFIED_BLOCKED" -> warnings.add("🚫 ${nodeMap[edge.fromNodeId]?.label} → ${nodeMap[edge.toNodeId]?.label}: ${if (edge.routeStatus == "VERIFIED_BLOCKED") "verified" else "reported"} BLOCKED")
            }
        }

        return RouteResult(
            path = path,
            pathLabels = pathLabels,
            totalDistance = totalDistance,
            totalCost = dist[toNodeId] ?: 0.0,
            edgesUsed = edgesUsed,
            warnings = warnings,
            isComplete = true
        )
    }

    private fun computeEdgeCost(
        edge: MapEdgeEntity,
        riskPenalty: Double,
        uncertaintyPenalty: Double
    ): Double {
        val baseCost = edge.distance.toDouble()

        val penalty = when (edge.routeStatus) {
            "REPORTED_BLOCKED", "VERIFIED_BLOCKED" -> BLOCKED_COST
            "CONFLICTED" -> riskPenalty
            "UNKNOWN" -> uncertaintyPenalty
            "REPORTED_OPEN" -> uncertaintyPenalty / 2
            "VERIFIED_OPEN" -> 0.0
            else -> uncertaintyPenalty / 2
        }

        return baseCost + penalty
    }

    /**
     * Find unvisited or poorly-verified areas that need exploration.
     * Advisory only — never autonomously directs responders to hazardous areas.
     */
    suspend fun suggestExploration(missionId: String): List<String> {
        val edges = mapDao.getAllEdges(missionId)
        val unknownEdges = edges.filter { it.routeStatus == "UNKNOWN" }

        // Return the node IDs adjacent to unknown edges
        val nodeIds = mutableSetOf<String>()
        for (edge in unknownEdges) {
            nodeIds.add(edge.fromNodeId)
            nodeIds.add(edge.toNodeId)
        }

        return nodeIds.toList()
    }
}
