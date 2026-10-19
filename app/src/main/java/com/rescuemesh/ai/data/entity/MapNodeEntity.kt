package com.rescuemesh.ai.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A vertex in the operational map graph.
 * Represents a real surveyed location: room, corridor junction, entrance, stairway, etc.
 */
@Entity(tableName = "map_nodes")
data class MapNodeEntity(
    @PrimaryKey val nodeId: String,
    val missionId: String,
    val label: String,                    // Human-readable: "Main Entrance", "Room 204"
    val floor: Int = 0,                   // Floor level (0 = ground)
    val latitude: Double,
    val longitude: Double,
    val nodeType: String = "JUNCTION",    // ROOM, JUNCTION, ENTRANCE, STAIRWAY, EXIT
    val createdAt: Long = System.currentTimeMillis()
)
