package com.rescuemesh.ai.model

import kotlinx.serialization.Serializable

/**
 * Sync protocol messages exchanged between peers over Nearby Connections.
 * Serialized as JSON for simplicity and debuggability.
 */
@Serializable
sealed class SyncMessage {

    /** Message type discriminator for JSON deserialization */
    abstract val type: String

    /**
     * Initial handshake when peers connect.
     * Establishes identity and mission context.
     */
    @Serializable
    data class Hello(
        override val type: String = "HELLO",
        val deviceId: String,
        val deviceName: String,
        val missionId: String,
        val protocolVersion: Int = 1
    ) : SyncMessage()

    /**
     * State digest: advertises which observations this device has.
     * Maps authorDeviceId → latestSequenceNumber.
     * Peers compare digests to discover missing events.
     */
    @Serializable
    data class StateDigest(
        override val type: String = "STATE_DIGEST",
        val deviceId: String,
        val sequences: Map<String, Long>   // authorDeviceId → max sequence
    ) : SyncMessage()

    /**
     * Request specific missing events by their IDs.
     */
    @Serializable
    data class EventRequest(
        override val type: String = "EVENT_REQUEST",
        val requestorDeviceId: String,
        val missingFromAuthors: Map<String, Long>  // authorDeviceId → afterSequence
    ) : SyncMessage()

    /**
     * Batch of observation events being transferred.
     */
    @Serializable
    data class EventBatch(
        override val type: String = "EVENT_BATCH",
        val senderDeviceId: String,
        val events: List<SyncObservation>
    ) : SyncMessage()

    /**
     * Acknowledgement of received events.
     */
    @Serializable
    data class EventAck(
        override val type: String = "EVENT_ACK",
        val deviceId: String,
        val acknowledgedEventIds: List<String>
    ) : SyncMessage()

    /**
     * Stage 2 on-demand request for specific photo evidence payload.
     */
    @Serializable
    data class EvidenceRequest(
        override val type: String = "EVIDENCE_REQUEST",
        val missionId: String,
        val eventId: String,
        val evidenceHash: String
    ) : SyncMessage()

    /**
     * Stage 2 header correlating Nearby Connections 64-bit payloadId to Observation evidence file.
     */
    @Serializable
    data class EvidenceHeader(
        override val type: String = "EVIDENCE_HEADER",
        val missionId: String,
        val eventId: String,
        val evidenceHash: String,
        val payloadId: Long,
        val fileSizeBytes: Long
    ) : SyncMessage()

    /**
     * Stage 2 acknowledgment of evidence payload receipt and verification status.
     */
    @Serializable
    data class EvidenceAck(
        override val type: String = "EVIDENCE_ACK",
        val missionId: String,
        val eventId: String,
        val evidenceHash: String,
        val verified: Boolean
    ) : SyncMessage()

    /**
     * Emergency broadcast chat message or SOS-linked incident thread message.
     */
    @Serializable
    data class ChatMessage(
        override val type: String = "CHAT_MESSAGE",
        val messageId: String,
        val missionId: String,
        val senderDeviceId: String,
        val senderName: String,
        val text: String,
        val timestamp: Long,
        val authorSequence: Long = 0L,
        val senderRole: String = "UNVERIFIED_PARTICIPANT",
        val linkedEventId: String? = null,
        val relayHopsRemaining: Int = 3
    ) : SyncMessage()

    /**
     * Acknowledgement of received chat messages.
     */
    @Serializable
    data class ChatAck(
        override val type: String = "CHAT_ACK",
        val deviceId: String,
        val acknowledgedMessageIds: List<String>
    ) : SyncMessage()

    /**
     * Consensual ephemeral peer live location beacon (10-minute TTL).
     * Volatile in-memory only; never persisted to SQLite Room database.
     */
    @Serializable
    data class PeerLocation(
        override val type: String = "PEER_LOCATION",
        val deviceId: String,
        val deviceName: String,
        val missionId: String,
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float? = null,
        val bearing: Float? = null,
        val speed: Float? = null,
        val altitude: Double? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) : SyncMessage()

    /**
     * Explicit revocation of ephemeral peer live location sharing.
     */
    @Serializable
    data class PeerLocationRevoked(
        override val type: String = "PEER_LOCATION_REVOKED",
        val deviceId: String,
        val missionId: String
    ) : SyncMessage()
}

/**
 * Serializable observation for sync transfer.
 * Mirrors ObservationEntity but without Room-specific annotations.
 */
@Serializable
data class SyncObservation(
    val eventId: String,
    val missionId: String,
    val authorDeviceId: String,
    val authorSequence: Long,
    val eventType: String,
    val title: String,
    val description: String = "",
    val reportedStatus: String? = null,
    val sourceType: String = "HUMAN_OBSERVATION",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val mapEdgeId: String? = null,
    val mapNodeId: String? = null,
    val evidenceHash: String? = null,
    val verificationStatus: String = "UNVERIFIED",
    val observedAtDevice: Long,
    val relayHopsRemaining: Int = 3
)

/**
 * Wrapper for JSON serialization with type discriminator.
 */
@Serializable
data class SyncMessageEnvelope(
    val type: String,
    val payload: String   // JSON string of the actual message
)
