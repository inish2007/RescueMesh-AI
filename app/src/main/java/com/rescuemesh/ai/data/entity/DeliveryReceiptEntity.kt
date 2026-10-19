package com.rescuemesh.ai.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Records that a specific observation event was successfully delivered to a specific peer.
 * Used for duplicate suppression and delivery tracking.
 */
@Entity(tableName = "delivery_receipts")
data class DeliveryReceiptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,
    val peerDeviceId: String,
    val deliveredAt: Long = System.currentTimeMillis(),
    val bytesTransferred: Int = 0,
    val latencyMs: Long? = null               // Time from outbox creation to ACK
)
