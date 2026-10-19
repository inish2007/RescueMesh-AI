package com.rescuemesh.ai.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An edge in the operational map graph.
 * Represents a physically surveyed connection between two locations.
 *
 * Route status follows conflict-preserving rules:
 * - UNKNOWN → first report sets it
 * - Agreeing reports confirm the status
 * - Disagreeing reports set CONFLICTED
 * - Only a VERIFIED review resolves CONFLICTED
 * - REPORTED_BLOCKED is NOT automatically cleared by time
 */
@Entity(tableName = "map_edges")
data class MapEdgeEntity(
    @PrimaryKey val edgeId: String,
    val missionId: String,
    val fromNodeId: String,
    val toNodeId: String,
    val distance: Float,                             // Meters (surveyed or estimated)
    val routeStatus: String = "UNKNOWN",             // UNKNOWN, REPORTED_OPEN, REPORTED_BLOCKED, CONFLICTED, VERIFIED_OPEN, VERIFIED_BLOCKED
    val lastObservationId: String? = null,            // Links to the observation that last modified this
    val conflictingObservationIds: String? = null,    // Comma-separated event IDs when CONFLICTED
    val updatedAt: Long = System.currentTimeMillis()
)
