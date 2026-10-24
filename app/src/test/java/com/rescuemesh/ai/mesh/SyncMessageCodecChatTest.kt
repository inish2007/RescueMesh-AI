package com.rescuemesh.ai.mesh

import com.rescuemesh.ai.model.SyncMessage
import com.rescuemesh.ai.model.SyncMessageCodec
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit Test Suite for SyncMessageCodec chat protocol messages:
 * Validates serialization, deserialization, envelope handling, and wire byte calculation
 * for SyncMessage.ChatMessage and SyncMessage.ChatAck.
 */
class SyncMessageCodecChatTest {

    @Test
    fun testChatMessageRoundtrip() {
        val chat = SyncMessage.ChatMessage(
            messageId = "chat-uuid-001",
            missionId = "MISSION_SECTOR_A",
            senderDeviceId = "DEVICE_ALPHA",
            senderName = "Rescue Team 1",
            text = "Structural instability observed at sector 4 entrance.",
            timestamp = 1729760000000L,
            authorSequence = 42L,
            senderRole = "UNVERIFIED_PARTICIPANT",
            linkedEventId = "sos-evt-999",
            relayHopsRemaining = 3
        )

        val serialized = SyncMessageCodec.serializeToString(chat)
        assertTrue("Envelope must have CHAT_MESSAGE type", serialized.contains("\"type\":\"CHAT_MESSAGE\""))

        val deserialized = SyncMessageCodec.deserializeFromString(serialized)
        assertTrue(deserialized is SyncMessage.ChatMessage)

        val restored = deserialized as SyncMessage.ChatMessage
        assertEquals("chat-uuid-001", restored.messageId)
        assertEquals("MISSION_SECTOR_A", restored.missionId)
        assertEquals("DEVICE_ALPHA", restored.senderDeviceId)
        assertEquals("Rescue Team 1", restored.senderName)
        assertEquals("Structural instability observed at sector 4 entrance.", restored.text)
        assertEquals(1729760000000L, restored.timestamp)
        assertEquals(42L, restored.authorSequence)
        assertEquals("UNVERIFIED_PARTICIPANT", restored.senderRole)
        assertEquals("sos-evt-999", restored.linkedEventId)
        assertEquals(3, restored.relayHopsRemaining)
    }

    @Test
    fun testChatAckRoundtrip() {
        val ack = SyncMessage.ChatAck(
            deviceId = "DEVICE_BETA",
            acknowledgedMessageIds = listOf("chat-uuid-001", "chat-uuid-002", "chat-uuid-003")
        )

        val serialized = SyncMessageCodec.serializeToString(ack)
        assertTrue("Envelope must have CHAT_ACK type", serialized.contains("\"type\":\"CHAT_ACK\""))

        val deserialized = SyncMessageCodec.deserializeFromString(serialized)
        assertTrue(deserialized is SyncMessage.ChatAck)

        val restored = deserialized as SyncMessage.ChatAck
        assertEquals("DEVICE_BETA", restored.deviceId)
        assertEquals(3, restored.acknowledgedMessageIds.size)
        assertEquals("chat-uuid-001", restored.acknowledgedMessageIds[0])
        assertEquals("chat-uuid-002", restored.acknowledgedMessageIds[1])
        assertEquals("chat-uuid-003", restored.acknowledgedMessageIds[2])
    }

    @Test
    fun testWireByteSizeCalculationForChat() {
        val shortChat = SyncMessage.ChatMessage(
            messageId = "uuid-1",
            missionId = "M1",
            senderDeviceId = "D1",
            senderName = "Op",
            text = "OK",
            timestamp = 1000L
        )

        val shortBytes = SyncMessageCodec.calculatePayloadBytes(shortChat)
        assertTrue("Short chat wire size must be compact (< 400 bytes)", shortBytes in 100..400)

        val maxText = "A".repeat(500)
        val maxChat = SyncMessage.ChatMessage(
            messageId = "uuid-2",
            missionId = "M1",
            senderDeviceId = "D1",
            senderName = "Operator",
            text = maxText,
            timestamp = 1000L,
            linkedEventId = "sos-123"
        )

        val maxBytes = SyncMessageCodec.calculatePayloadBytes(maxChat)
        assertTrue("Max length chat message must be <= 900 bytes on wire", maxBytes <= 900)
    }

    @Test
    fun testBinarySerializationDeserialization() {
        val chat = SyncMessage.ChatMessage(
            messageId = "binary-test-id",
            missionId = "MISSION_DELTA",
            senderDeviceId = "DEV_03",
            senderName = "Paramedic Bravo",
            text = "Supplies needed: oxygen, bandages.",
            timestamp = 555555L,
            authorSequence = 7L,
            relayHopsRemaining = 2
        )

        val bytes = SyncMessageCodec.serialize(chat)
        val restored = SyncMessageCodec.deserialize(bytes) as SyncMessage.ChatMessage

        assertEquals(chat.messageId, restored.messageId)
        assertEquals(chat.text, restored.text)
        assertEquals(chat.authorSequence, restored.authorSequence)
        assertEquals(chat.relayHopsRemaining, restored.relayHopsRemaining)
    }
}
