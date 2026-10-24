package com.rescuemesh.ai.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SyncMessageTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun serializeToEnvelope(message: SyncMessage): String {
        val payload = when (message) {
            is SyncMessage.Hello -> json.encodeToString(message)
            is SyncMessage.StateDigest -> json.encodeToString(message)
            is SyncMessage.EventRequest -> json.encodeToString(message)
            is SyncMessage.EventBatch -> json.encodeToString(message)
            is SyncMessage.EventAck -> json.encodeToString(message)
            is SyncMessage.EvidenceRequest -> json.encodeToString(message)
            is SyncMessage.EvidenceHeader -> json.encodeToString(message)
            is SyncMessage.EvidenceAck -> json.encodeToString(message)
            is SyncMessage.ChatMessage -> json.encodeToString(message)
            is SyncMessage.ChatAck -> json.encodeToString(message)
        }
        return json.encodeToString(SyncMessageEnvelope(type = message.type, payload = payload))
    }

    private fun deserializeFromEnvelope(rawJson: String): SyncMessage {
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
            "CHAT_MESSAGE" -> json.decodeFromString<SyncMessage.ChatMessage>(envelope.payload)
            "CHAT_ACK" -> json.decodeFromString<SyncMessage.ChatAck>(envelope.payload)
            else -> throw IllegalArgumentException("Unknown message type: ${envelope.type}")
        }
    }

    @Test
    fun testSyncMessageEnvelopeDirectSerialization() {
        val envelope = SyncMessageEnvelope(type = "TEST_TYPE", payload = "{\"data\":\"test\"}")
        val serialized = json.encodeToString(envelope)
        assertTrue(serialized.contains("\"type\":\"TEST_TYPE\""))
        assertTrue(serialized.contains("\"payload\":\"{\\\"data\\\":\\\"test\\\"}\""))

        val deserialized = json.decodeFromString<SyncMessageEnvelope>(serialized)
        assertEquals("TEST_TYPE", deserialized.type)
        assertEquals("{\"data\":\"test\"}", deserialized.payload)
    }

    @Test
    fun testHelloMessageSerializationAndEnvelopeRoundTrip() {
        val hello = SyncMessage.Hello(
            deviceId = "dev-alpha-001",
            deviceName = "Alpha Responder",
            missionId = "mission-disaster-zone-1",
            protocolVersion = 1
        )

        // Direct serialization
        val directJson = json.encodeToString(hello)
        assertTrue(directJson.contains("\"type\":\"HELLO\""))
        assertTrue(directJson.contains("\"deviceId\":\"dev-alpha-001\""))
        val decodedHello = json.decodeFromString<SyncMessage.Hello>(directJson)
        assertEquals(hello, decodedHello)

        // Envelope serialization round-trip
        val envelopeJson = serializeToEnvelope(hello)
        val deserialized = deserializeFromEnvelope(envelopeJson)
        assertTrue(deserialized is SyncMessage.Hello)
        val result = deserialized as SyncMessage.Hello
        assertEquals("HELLO", result.type)
        assertEquals("dev-alpha-001", result.deviceId)
        assertEquals("Alpha Responder", result.deviceName)
        assertEquals("mission-disaster-zone-1", result.missionId)
        assertEquals(1, result.protocolVersion)
    }

    @Test
    fun testStateDigestSerializationAndEnvelopeRoundTrip() {
        val sequences = mapOf(
            "dev-alpha" to 42L,
            "dev-bravo" to 105L,
            "dev-charlie" to 1L
        )
        val digest = SyncMessage.StateDigest(
            deviceId = "dev-alpha",
            sequences = sequences
        )

        // Direct serialization
        val directJson = json.encodeToString(digest)
        assertTrue(directJson.contains("\"type\":\"STATE_DIGEST\""))
        val decodedDigest = json.decodeFromString<SyncMessage.StateDigest>(directJson)
        assertEquals(digest, decodedDigest)

        // Envelope serialization round-trip
        val envelopeJson = serializeToEnvelope(digest)
        val deserialized = deserializeFromEnvelope(envelopeJson)
        assertTrue(deserialized is SyncMessage.StateDigest)
        val result = deserialized as SyncMessage.StateDigest
        assertEquals("dev-alpha", result.deviceId)
        assertEquals(3, result.sequences.size)
        assertEquals(42L, result.sequences["dev-alpha"])
        assertEquals(105L, result.sequences["dev-bravo"])
        assertEquals(1L, result.sequences["dev-charlie"])
    }

    @Test
    fun testStateDigestWithEmptySequences() {
        val emptyDigest = SyncMessage.StateDigest(
            deviceId = "dev-new",
            sequences = emptyMap()
        )
        val envelopeJson = serializeToEnvelope(emptyDigest)
        val deserialized = deserializeFromEnvelope(envelopeJson) as SyncMessage.StateDigest
        assertEquals("dev-new", deserialized.deviceId)
        assertTrue(deserialized.sequences.isEmpty())
    }

    @Test
    fun testEventRequestSerializationAndEnvelopeRoundTrip() {
        val missingMap = mapOf(
            "dev-bravo" to 10L,
            "dev-delta" to 0L
        )
        val request = SyncMessage.EventRequest(
            requestorDeviceId = "dev-alpha",
            missingFromAuthors = missingMap
        )

        // Direct serialization
        val directJson = json.encodeToString(request)
        assertTrue(directJson.contains("\"type\":\"EVENT_REQUEST\""))
        val decodedRequest = json.decodeFromString<SyncMessage.EventRequest>(directJson)
        assertEquals(request, decodedRequest)

        // Envelope serialization round-trip
        val envelopeJson = serializeToEnvelope(request)
        val deserialized = deserializeFromEnvelope(envelopeJson)
        assertTrue(deserialized is SyncMessage.EventRequest)
        val result = deserialized as SyncMessage.EventRequest
        assertEquals("dev-alpha", result.requestorDeviceId)
        assertEquals(missingMap, result.missingFromAuthors)
    }

    @Test
    fun testEventBatchSerializationAndEnvelopeRoundTrip() {
        val obs1 = SyncObservation(
            eventId = "evt-001",
            missionId = "mission-1",
            authorDeviceId = "dev-alpha",
            authorSequence = 1L,
            eventType = "HAZARD",
            title = "Gas Leak",
            description = "Smell of gas near basement",
            reportedStatus = "BLOCKED",
            sourceType = "HUMAN_OBSERVATION",
            latitude = 40.7128,
            longitude = -74.0060,
            accuracy = 4.5f,
            mapEdgeId = "edge-b1",
            mapNodeId = "node-b1",
            verificationStatus = "UNVERIFIED",
            observedAtDevice = 1700000000000L,
            relayHopsRemaining = 3
        )
        val obs2 = SyncObservation(
            eventId = "evt-002",
            missionId = "mission-1",
            authorDeviceId = "dev-alpha",
            authorSequence = 2L,
            eventType = "ROUTE_STATUS",
            title = "Corridor Clear",
            description = "Debris cleared by rescue team",
            reportedStatus = "OPEN",
            sourceType = "HUMAN_OBSERVATION",
            latitude = null,
            longitude = null,
            accuracy = null,
            mapEdgeId = "edge-c2",
            mapNodeId = null,
            verificationStatus = "VERIFIED",
            observedAtDevice = 1700000005000L,
            relayHopsRemaining = 2
        )

        val batch = SyncMessage.EventBatch(
            senderDeviceId = "dev-alpha",
            events = listOf(obs1, obs2)
        )

        // Direct serialization
        val directJson = json.encodeToString(batch)
        assertTrue(directJson.contains("\"type\":\"EVENT_BATCH\""))
        val decodedBatch = json.decodeFromString<SyncMessage.EventBatch>(directJson)
        assertEquals(batch, decodedBatch)

        // Envelope serialization round-trip
        val envelopeJson = serializeToEnvelope(batch)
        val deserialized = deserializeFromEnvelope(envelopeJson)
        assertTrue(deserialized is SyncMessage.EventBatch)
        val result = deserialized as SyncMessage.EventBatch
        assertEquals("dev-alpha", result.senderDeviceId)
        assertEquals(2, result.events.size)

        val firstObs = result.events[0]
        assertEquals("evt-001", firstObs.eventId)
        assertEquals("Gas Leak", firstObs.title)
        assertEquals(40.7128, firstObs.latitude!!, 1e-6)
        assertEquals(-74.0060, firstObs.longitude!!, 1e-6)
        assertEquals(4.5f, firstObs.accuracy!!, 1e-3f)
        assertEquals(3, firstObs.relayHopsRemaining)

        val secondObs = result.events[1]
        assertEquals("evt-002", secondObs.eventId)
        assertNull(secondObs.latitude)
        assertEquals("VERIFIED", secondObs.verificationStatus)
        assertEquals(2, secondObs.relayHopsRemaining)
    }

    @Test
    fun testEmptyEventBatchSerialization() {
        val emptyBatch = SyncMessage.EventBatch(
            senderDeviceId = "dev-alpha",
            events = emptyList()
        )
        val envelopeJson = serializeToEnvelope(emptyBatch)
        val deserialized = deserializeFromEnvelope(envelopeJson) as SyncMessage.EventBatch
        assertEquals("dev-alpha", deserialized.senderDeviceId)
        assertTrue(deserialized.events.isEmpty())
    }

    @Test
    fun testEventAckSerializationAndEnvelopeRoundTrip() {
        val ack = SyncMessage.EventAck(
            deviceId = "dev-beta",
            acknowledgedEventIds = listOf("evt-100", "evt-101", "evt-102")
        )

        // Direct serialization
        val directJson = json.encodeToString(ack)
        assertTrue(directJson.contains("\"type\":\"EVENT_ACK\""))
        val decodedAck = json.decodeFromString<SyncMessage.EventAck>(directJson)
        assertEquals(ack, decodedAck)

        // Envelope serialization round-trip
        val envelopeJson = serializeToEnvelope(ack)
        val deserialized = deserializeFromEnvelope(envelopeJson)
        assertTrue(deserialized is SyncMessage.EventAck)
        val result = deserialized as SyncMessage.EventAck
        assertEquals("dev-beta", result.deviceId)
        assertEquals(listOf("evt-100", "evt-101", "evt-102"), result.acknowledgedEventIds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testUnknownEnvelopeTypeThrowsIllegalArgumentException() {
        val unknownEnvelopeJson = json.encodeToString(
            SyncMessageEnvelope(type = "UNRECOGNIZED_COMMAND", payload = "{}")
        )
        deserializeFromEnvelope(unknownEnvelopeJson)
    }
}
