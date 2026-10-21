package com.rescuemesh.ai.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Durable outbound message queue.
 * Events are placed here before transmission and removed only after acknowledgement.
 * If the app crashes or connection drops, queued events survive and retry.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,                     // FK to observations
    val targetPeerId: String? = null,        // null = broadcast to all connected peers
    val priority: Float = 0f,                // Computed by PriorityScheduler
    val schedulerType: String = "VOI",       // VOI, FIFO, RANDOM — which scheduler scored it
    val retryCount: Int = 0,
    val nextAttemptAt: Long = 0L,
    val maxRetries: Int = 5,
    val maxHops: Int = 3,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAttemptAt: Long? = null,
    val status: String = "PENDING"           // PENDING, SENDING, DELIVERED, FAILED, EXPIRED
)
