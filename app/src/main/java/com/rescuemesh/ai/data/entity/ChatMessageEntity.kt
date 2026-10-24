package com.rescuemesh.ai.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing an emergency broadcast chat or SOS-linked incident thread message
 * stored locally in SQLite via Room.
 *
 * Implements strict offline-first principles:
 * - Every message authored locally is committed with status "QUEUED" or "SENDING" before mesh transmission.
 * - Every message received from peers is committed immediately with deduplication on messageId.
 * - Distinguishes between UNVERIFIED_PARTICIPANT and VERIFIED_RESPONDER per Decision 3 / Astra G3-S13.
 * - Sequence numbers provide monotonic author ordering resilient to clock skew.
 * - Relay hops remaining prevents infinite gossip loops in multi-hop mesh topologies.
 */
@Entity(
    tableName = "chat_messages",
    indices = [
        Index(value = ["messageId"], unique = true),
        Index(value = ["missionId"]),
        Index(value = ["timestamp"]),
        Index(value = ["status"]),
        Index(value = ["linkedEventId"])
    ]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: String,                      // Globally unique message UUID
    val missionId: String,                      // Incident / mission isolation boundary
    val senderId: String,                       // Device identifier of the author
    val senderName: String,                     // Tactical callsign or display name
    val text: String,                           // Text message body (max 500 characters)
    val timestamp: Long,                        // Milliseconds UTC when message was authored
    val status: String,                         // "SENDING", "QUEUED", "SENT_TO_PEERS", "DELIVERED_TO_ALL", "FAILED", "RECEIVED"
    val ackPeerCount: Int = 0,                  // Number of unique peer acks received
    val isLocal: Boolean = false,               // True if authored on this device
    val senderRole: String = ROLE_UNVERIFIED,   // ROLE_UNVERIFIED or ROLE_VERIFIED_RESPONDER
    val linkedEventId: String? = null,          // Optional reference to a specific SOS or observation eventId
    val authorSequence: Long = 0L,              // Monotonic sequence per author device
    val relayHopsRemaining: Int = 3             // Hops remaining for multi-hop mesh relay
) {
    companion object {
        const val ROLE_UNVERIFIED = "UNVERIFIED_PARTICIPANT"
        const val ROLE_VERIFIED_RESPONDER = "VERIFIED_RESPONDER"

        const val STATUS_SENDING = "SENDING"
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_SENT_TO_PEERS = "SENT_TO_PEERS"
        const val STATUS_DELIVERED_TO_ALL = "DELIVERED_TO_ALL"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_RECEIVED = "RECEIVED"

        const val MAX_TEXT_LENGTH = 500
    }
}
