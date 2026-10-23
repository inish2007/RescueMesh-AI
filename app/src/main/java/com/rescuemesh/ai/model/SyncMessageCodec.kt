package com.rescuemesh.ai.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Shared wire serialization / deserialization codec for sync protocol messages.
 * Uses JSON envelope format with type discriminator:
 *   {"type": "<TYPE>", "payload": "<JSON string of message>"}
 *
 * Provides exact wire payload byte measurement for bandwidth tracking and R3 benchmarks.
 */
object SyncMessageCodec {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Serializes a SyncMessage into its JSON string representation inside a SyncMessageEnvelope.
     */
    fun serializeToString(message: SyncMessage): String {
        val payload = when (message) {
            is SyncMessage.Hello -> json.encodeToString(message)
            is SyncMessage.StateDigest -> json.encodeToString(message)
            is SyncMessage.EventRequest -> json.encodeToString(message)
            is SyncMessage.EventBatch -> json.encodeToString(message)
            is SyncMessage.EventAck -> json.encodeToString(message)
            is SyncMessage.EvidenceRequest -> json.encodeToString(message)
            is SyncMessage.EvidenceHeader -> json.encodeToString(message)
            is SyncMessage.EvidenceAck -> json.encodeToString(message)
        }
        return json.encodeToString(SyncMessageEnvelope(type = message.type, payload = payload))
    }

    /**
     * Serializes a SyncMessage into a UTF-8 encoded ByteArray.
     */
    fun serialize(message: SyncMessage): ByteArray {
        return serializeToString(message).toByteArray(Charsets.UTF_8)
    }

    /**
     * Deserializes a JSON string containing a SyncMessageEnvelope into a concrete SyncMessage.
     */
    fun deserializeFromString(rawJson: String): SyncMessage {
        val envelope = json.decodeFromString<SyncMessageEnvelope>(rawJson)
        return when (envelope.type) {
            "HELLO" -> json.decodeFromString<SyncMessage.Hello>(envelope.payload)
            "STATE_DIGEST" -> json.decodeFromString<SyncMessage.StateDigest>(envelope.payload)
            "EVENT_REQUEST" -> json.decodeFromString<SyncMessage.EventRequest>(envelope.payload)
            "EVENT_BATCH" -> json.decodeFromString<SyncMessage.EventBatch>(envelope.payload)
            "EVENT_ACK" -> json.decodeFromString<SyncMessage.EventAck>(envelope.payload)
            "EVIDENCE_REQUEST" -> json.decodeFromString<SyncMessage.EvidenceRequest>(envelope.payload)
            "EVIDENCE_HEADER" -> json.decodeFromString<SyncMessage.EvidenceHeader>(envelope.payload)
            "EVIDENCE_ACK" -> json.decodeFromString<SyncMessage.EvidenceAck>(envelope.payload)
            else -> throw IllegalArgumentException("Unknown message type: ${envelope.type}")
        }
    }

    /**
     * Deserializes a UTF-8 encoded ByteArray into a concrete SyncMessage.
     */
    fun deserialize(bytes: ByteArray): SyncMessage {
        return deserializeFromString(String(bytes, Charsets.UTF_8))
    }

    /**
     * Calculates the exact wire payload byte count of a message.
     * Used for byte-budget windowed transmission (Feature 11) and R3 bandwidth benchmarks.
     */
    fun calculatePayloadBytes(message: SyncMessage): Int {
        return serialize(message).size
    }
}
