package com.rescuemesh.ai.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents an active mission/operation.
 * All observations and map data are scoped to a mission.
 */
@Entity(tableName = "missions")
data class MissionEntity(
    @PrimaryKey val missionId: String,
    val name: String,
    val pairingCode: String = "",
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
)
