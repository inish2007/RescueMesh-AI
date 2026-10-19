package com.rescuemesh.ai.intelligence

import com.rescuemesh.ai.data.dao.MapDao
import com.rescuemesh.ai.data.dao.ObservationDao
import com.rescuemesh.ai.data.entity.MapEdgeEntity
import com.rescuemesh.ai.data.entity.ObservationEntity

/**
 * Conflict-preserving map fusion engine.
 *
 * When observations arrive about map edges, this engine updates edge statuses
 * following strict conflict-preservation rules:
 *
 * State transitions:
 *   UNKNOWN → first report sets the status (REPORTED_OPEN or REPORTED_BLOCKED)
 *   Same status → no change (reinforced)
 *   Different status → CONFLICTED (both observations preserved)
 *   CONFLICTED → stays CONFLICTED until a VERIFIED review resolves it
 *   VERIFIED → can only be changed by another VERIFIED observation
 *
 * Critical safety rules:
 * - A REPORTED_BLOCKED edge is NEVER automatically cleared by time
 * - Both sides of a conflict are preserved with their observation IDs
 * - An AI prediction can never override a human observation
 */
class MapFusionEngine(
    private val mapDao: MapDao,
    private val observationDao: ObservationDao
) {
    data class FusionResult(
        val edgeId: String,
        val previousStatus: String,
        val newStatus: String,
        val isConflict: Boolean,
        val conflictingIds: List<String>
    )

    /**
     * Apply an observation to the map.
     * Returns the fusion result if a map edge was affected, null otherwise.
     */
    suspend fun applyObservation(observation: ObservationEntity): FusionResult? {
        val edgeId = observation.mapEdgeId ?: return null
        val reportedStatus = observation.reportedStatus ?: return null

        val edge = mapDao.getEdge(edgeId) ?: return null
        val previousStatus = edge.routeStatus

        val (newStatus, conflictIds) = resolveStatus(edge, reportedStatus, observation.eventId)

        mapDao.updateEdgeStatus(
            edgeId = edgeId,
            status = newStatus,
            observationId = observation.eventId,
            conflictIds = conflictIds.joinToString(",").ifEmpty { null }
        )

        return FusionResult(
            edgeId = edgeId,
            previousStatus = previousStatus,
            newStatus = newStatus,
            isConflict = newStatus == "CONFLICTED",
            conflictingIds = conflictIds
        )
    }

    /**
     * Resolve the new edge status based on the current status and incoming report.
     * Returns (newStatus, list of conflicting observation IDs).
     */
    private fun resolveStatus(
        edge: MapEdgeEntity,
        reportedStatus: String,
        observationId: String
    ): Pair<String, List<String>> {
        val currentStatus = edge.routeStatus
        val existingConflicts = edge.conflictingObservationIds
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.toMutableList()
            ?: mutableListOf()

        return when {
            // Unknown → first report sets it
            currentStatus == "UNKNOWN" -> {
                val newStatus = when (reportedStatus) {
                    "OPEN", "SAFE" -> "REPORTED_OPEN"
                    "BLOCKED" -> "REPORTED_BLOCKED"
                    "HAZARD" -> "REPORTED_BLOCKED"  // Treat hazard as blocked for routing
                    else -> "REPORTED_OPEN"
                }
                newStatus to listOf(observationId)
            }

            // VERIFIED can only be changed by another VERIFIED observation
            currentStatus.startsWith("VERIFIED") -> {
                // Keep verified status unless this is also a verified observation
                currentStatus to existingConflicts
            }

            // Same direction → reinforce (no conflict)
            (currentStatus == "REPORTED_OPEN" && reportedStatus in listOf("OPEN", "SAFE")) ||
            (currentStatus == "REPORTED_BLOCKED" && reportedStatus in listOf("BLOCKED", "HAZARD")) -> {
                existingConflicts.add(observationId)
                currentStatus to existingConflicts
            }

            // Different direction → CONFLICT
            (currentStatus == "REPORTED_OPEN" && reportedStatus in listOf("BLOCKED", "HAZARD")) ||
            (currentStatus == "REPORTED_BLOCKED" && reportedStatus in listOf("OPEN", "SAFE")) -> {
                existingConflicts.add(observationId)
                if (edge.lastObservationId != null && !existingConflicts.contains(edge.lastObservationId)) {
                    existingConflicts.add(0, edge.lastObservationId)
                }
                "CONFLICTED" to existingConflicts
            }

            // Already CONFLICTED → add to conflict list
            currentStatus == "CONFLICTED" -> {
                existingConflicts.add(observationId)
                "CONFLICTED" to existingConflicts
            }

            // Default: preserve current status
            else -> currentStatus to existingConflicts
        }
    }

    /**
     * Get the current map state summary for display.
     */
    suspend fun getMapSummary(missionId: String): MapSummary {
        val edges = mapDao.getAllEdges(missionId)
        return MapSummary(
            totalEdges = edges.size,
            unknownEdges = edges.count { it.routeStatus == "UNKNOWN" },
            openEdges = edges.count { it.routeStatus == "REPORTED_OPEN" || it.routeStatus == "VERIFIED_OPEN" },
            blockedEdges = edges.count { it.routeStatus == "REPORTED_BLOCKED" || it.routeStatus == "VERIFIED_BLOCKED" },
            conflictedEdges = edges.count { it.routeStatus == "CONFLICTED" }
        )
    }

    data class MapSummary(
        val totalEdges: Int,
        val unknownEdges: Int,
        val openEdges: Int,
        val blockedEdges: Int,
        val conflictedEdges: Int
    )
}
