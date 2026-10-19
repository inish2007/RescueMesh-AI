package com.rescuemesh.ai.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Generic metrics/audit event for research evaluation.
 * Captures scheduler decisions, transfer statistics, connection events, and system state.
 */
@Entity(tableName = "metrics_events")
data class MetricsEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val metricType: String,                  // SEND, RECEIVE, QUEUE_DECISION, DELIVERY, CONNECTION, DISCONNECT, SYNC, SCHEDULER_COMPARISON
    val eventId: String? = null,             // Related observation event ID
    val peerId: String? = null,              // Related peer device ID
    val bytesTransferred: Int? = null,
    val latencyMs: Long? = null,
    val schedulerType: String? = null,       // VOI, FIFO, RANDOM
    val priorityScore: Float? = null,
    val details: String? = null              // JSON string with additional breakdown
)
