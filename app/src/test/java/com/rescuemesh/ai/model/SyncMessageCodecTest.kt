package com.rescuemesh.ai.model

import com.rescuemesh.ai.data.entity.*
import com.rescuemesh.ai.fakes.FakeObservationDao
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SyncMessageCodecTest {

    @Test
    fun testHelloCodecRoundTrip() {
        val hello = SyncMessage.Hello(
            deviceId = "dev-alpha-001",
            deviceName = "Alpha Radio",
            missionId = "disaster-mission-42"
        )

        val bytes = SyncMessageCodec.serialize(hello)
        assertTrue(bytes.isNotEmpty())

        val deserialized = SyncMessageCodec.deserialize(bytes)
        assertTrue(deserialized is SyncMessage.Hello)

        val restored = deserialized as SyncMessage.Hello
        assertEquals("dev-alpha-001", restored.deviceId)
        assertEquals("Alpha Radio", restored.deviceName)
        assertEquals("disaster-mission-42", restored.missionId)
        assertEquals("HELLO", restored.type)
    }

    @Test
    fun testStateDigestCodecRoundTrip() {
        val digest = SyncMessage.StateDigest(
            deviceId = "dev-bravo-002",
            sequences = mapOf(
                "dev-alpha" to 15L,
                "dev-bravo" to 42L,
                "dev-charlie" to 7L
            )
        )

        val bytes = SyncMessageCodec.serialize(digest)
        val deserialized = SyncMessageCodec.deserialize(bytes) as SyncMessage.StateDigest

        assertEquals("dev-bravo-002", deserialized.deviceId)
        assertEquals(3, deserialized.sequences.size)
        assertEquals(15L, deserialized.sequences["dev-alpha"])
        assertEquals(42L, deserialized.sequences["dev-bravo"])
        assertEquals(7L, deserialized.sequences["dev-charlie"])
    }

    @Test
    fun testEventRequestCodecRoundTrip() {
        val request = SyncMessage.EventRequest(
            requestorDeviceId = "dev-charlie-003",
            missingFromAuthors = mapOf(
                "dev-alpha" to 10L,
                "dev-bravo" to 20L
            )
        )

        val bytes = SyncMessageCodec.serialize(request)
        val deserialized = SyncMessageCodec.deserialize(bytes) as SyncMessage.EventRequest

        assertEquals("dev-charlie-003", deserialized.requestorDeviceId)
        assertEquals(2, deserialized.missingFromAuthors.size)
        assertEquals(10L, deserialized.missingFromAuthors["dev-alpha"])
        assertEquals(20L, deserialized.missingFromAuthors["dev-bravo"])
    }

    @Test
    fun testEventBatchCodecRoundTrip() {
        val obs1 = SyncObservation(
            eventId = "evt-001",
            missionId = "mission-alpha",
            authorDeviceId = "dev-alpha",
            authorSequence = 1L,
            eventType = "HAZARD",
            title = "Gas Leak Detected",
            description = "High methane levels in basement",
            reportedStatus = "HAZARD",
            sourceType = "HUMAN",
            latitude = 37.7749,
            longitude = -122.4194,
            accuracy = 3.5f,
            mapEdgeId = "edge-12",
            mapNodeId = "node-5",
            verificationStatus = "UNVERIFIED",
            observedAtDevice = 1700000000000L,
            relayHopsRemaining = 3
        )
        val obs2 = SyncObservation(
            eventId = "evt-002",
            missionId = "mission-alpha",
            authorDeviceId = "dev-alpha",
            authorSequence = 2L,
            eventType = "ROUTE_STATUS",
            title = "West Passage Clear",
            description = "",
            reportedStatus = "OPEN",
            sourceType = "SENSOR",
            latitude = null,
            longitude = null,
            accuracy = null,
            mapEdgeId = "edge-15",
            mapNodeId = null,
            verificationStatus = "VERIFIED",
            observedAtDevice = 1700000001000L,
            relayHopsRemaining = 2
        )

        val batch = SyncMessage.EventBatch(
            senderDeviceId = "dev-alpha",
            events = listOf(obs1, obs2)
        )

        val bytes = SyncMessageCodec.serialize(batch)
        val deserialized = SyncMessageCodec.deserialize(bytes) as SyncMessage.EventBatch

        assertEquals("dev-alpha", deserialized.senderDeviceId)
        assertEquals(2, deserialized.events.size)

        val restored1 = deserialized.events[0]
        assertEquals("evt-001", restored1.eventId)
        assertEquals("HAZARD", restored1.eventType)
        assertEquals(37.7749, restored1.latitude!!, 1e-4)
        assertEquals(-122.4194, restored1.longitude!!, 1e-4)
        assertEquals(3.5f, restored1.accuracy!!, 1e-4f)
        assertEquals("edge-12", restored1.mapEdgeId)
        assertEquals(3, restored1.relayHopsRemaining)

        val restored2 = deserialized.events[1]
        assertEquals("evt-002", restored2.eventId)
        assertEquals("ROUTE_STATUS", restored2.eventType)
        assertNull(restored2.latitude)
        assertEquals("", restored2.description)
        assertEquals("OPEN", restored2.reportedStatus)
        assertEquals(2, restored2.relayHopsRemaining)
    }

    @Test
    fun testEventAckCodecRoundTrip() {
        val ack = SyncMessage.EventAck(
            deviceId = "dev-delta-004",
            acknowledgedEventIds = listOf("evt-1", "evt-2", "evt-3")
        )

        val bytes = SyncMessageCodec.serialize(ack)
        val deserialized = SyncMessageCodec.deserialize(bytes) as SyncMessage.EventAck

        assertEquals("dev-delta-004", deserialized.deviceId)
        assertEquals(listOf("evt-1", "evt-2", "evt-3"), deserialized.acknowledgedEventIds)
    }

    @Test
    fun testEvidenceRequestCodecRoundTrip() {
        val req = SyncMessage.EvidenceRequest(
            missionId = "mission-alpha",
            eventId = "evt-photo-001",
            evidenceHash = "a3f5b7c8d9e01234567890abcdef1234567890abcdef1234567890abcdef1234"
        )
        val bytes = SyncMessageCodec.serialize(req)
        assertTrue(bytes.isNotEmpty())

        val deserialized = SyncMessageCodec.deserialize(bytes)
        assertTrue(deserialized is SyncMessage.EvidenceRequest)

        val restored = deserialized as SyncMessage.EvidenceRequest
        assertEquals("mission-alpha", restored.missionId)
        assertEquals("evt-photo-001", restored.eventId)
        assertEquals("a3f5b7c8d9e01234567890abcdef1234567890abcdef1234567890abcdef1234", restored.evidenceHash)
        assertEquals("EVIDENCE_REQUEST", restored.type)
    }

    @Test
    fun testEvidenceHeaderCodecRoundTrip() {
        val header = SyncMessage.EvidenceHeader(
            missionId = "mission-alpha",
            eventId = "evt-photo-001",
            evidenceHash = "a3f5b7c8d9e01234567890abcdef1234567890abcdef1234567890abcdef1234",
            payloadId = 987654321012345L,
            fileSizeBytes = 2048576L
        )
        val bytes = SyncMessageCodec.serialize(header)
        val deserialized = SyncMessageCodec.deserialize(bytes) as SyncMessage.EvidenceHeader

        assertEquals("mission-alpha", deserialized.missionId)
        assertEquals("evt-photo-001", deserialized.eventId)
        assertEquals("a3f5b7c8d9e01234567890abcdef1234567890abcdef1234567890abcdef1234", deserialized.evidenceHash)
        assertEquals(987654321012345L, deserialized.payloadId)
        assertEquals(2048576L, deserialized.fileSizeBytes)
        assertEquals("EVIDENCE_HEADER", deserialized.type)
    }

    @Test
    fun testEvidenceAckCodecRoundTrip() {
        val ackVerified = SyncMessage.EvidenceAck(
            missionId = "mission-alpha",
            eventId = "evt-photo-001",
            evidenceHash = "a3f5b7c8d9e01234567890abcdef1234567890abcdef1234567890abcdef1234",
            verified = true
        )
        val bytes = SyncMessageCodec.serialize(ackVerified)
        val deserialized = SyncMessageCodec.deserialize(bytes) as SyncMessage.EvidenceAck

        assertEquals("mission-alpha", deserialized.missionId)
        assertEquals("evt-photo-001", deserialized.eventId)
        assertEquals("a3f5b7c8d9e01234567890abcdef1234567890abcdef1234567890abcdef1234", deserialized.evidenceHash)
        assertTrue(deserialized.verified)
        assertEquals("EVIDENCE_ACK", deserialized.type)

        val ackFailed = ackVerified.copy(verified = false)
        val failedRestored = SyncMessageCodec.deserialize(SyncMessageCodec.serialize(ackFailed)) as SyncMessage.EvidenceAck
        assertFalse(failedRestored.verified)
    }

    @Test
    fun testCalculatePayloadBytesMatchesSerializedByteArrayLength() {
        val messages: List<SyncMessage> = listOf(
            SyncMessage.Hello(deviceId = "dev-1", deviceName = "Device 1", missionId = "mission-1"),
            SyncMessage.StateDigest(deviceId = "dev-1", sequences = mapOf("a" to 1L, "b" to 2L)),
            SyncMessage.EventRequest(requestorDeviceId = "dev-1", missingFromAuthors = mapOf("a" to 1L)),
            SyncMessage.EventBatch(senderDeviceId = "dev-1", events = emptyList<SyncObservation>()),
            SyncMessage.EventAck(deviceId = "dev-1", acknowledgedEventIds = listOf("e1", "e2")),
            SyncMessage.EvidenceRequest(missionId = "m1", eventId = "e1", evidenceHash = "hash1"),
            SyncMessage.EvidenceHeader(missionId = "m1", eventId = "e1", evidenceHash = "hash1", payloadId = 12345L, fileSizeBytes = 1000L),
            SyncMessage.EvidenceAck(missionId = "m1", eventId = "e1", evidenceHash = "hash1", verified = true)
        )

        for (msg in messages) {
            val calculated = SyncMessageCodec.calculatePayloadBytes(msg)
            val actual = SyncMessageCodec.serialize(msg).size
            assertEquals("Byte count mismatch for ${msg::class.simpleName}", actual, calculated)
        }
    }

    @Test
    fun testIncrementalBatchPayloadSizeCalculation() {
        val baseBatch = SyncMessage.EventBatch(senderDeviceId = "dev-1", events = emptyList<SyncObservation>())
        val baseBytes = SyncMessageCodec.calculatePayloadBytes(baseBatch)

        val obs1 = SyncObservation(
            eventId = "evt-1",
            missionId = "m1",
            authorDeviceId = "dev-1",
            authorSequence = 1L,
            eventType = "NOTE",
            title = "Test note 1",
            observedAtDevice = 1000L
        )

        val singleEventBatch = SyncMessage.EventBatch(senderDeviceId = "dev-1", events = listOf(obs1))
        val singleBytes = SyncMessageCodec.calculatePayloadBytes(singleEventBatch)

        val obs2 = SyncObservation(
            eventId = "evt-2",
            missionId = "m1",
            authorDeviceId = "dev-1",
            authorSequence = 2L,
            eventType = "NOTE",
            title = "Test note 2",
            observedAtDevice = 2000L
        )

        val twoEventBatch = SyncMessage.EventBatch(senderDeviceId = "dev-1", events = listOf(obs1, obs2))
        val twoBytes = SyncMessageCodec.calculatePayloadBytes(twoEventBatch)

        assertTrue("Empty batch ($baseBytes) should be smaller than single ($singleBytes)", baseBytes < singleBytes)
        assertTrue("Single batch ($singleBytes) should be smaller than double ($twoBytes)", singleBytes < twoBytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testUnknownEnvelopeTypeThrowsIllegalArgumentException() {
        val fakeEnvelope = """{"type":"UNKNOWN_FUTURE_TYPE","payload":"{}"}"""
        SyncMessageCodec.deserializeFromString(fakeEnvelope)
    }

    @Test(expected = SerializationException::class)
    fun testMalformedBytesThrowsSerializationException() {
        val malformed = "Definitely not valid json payload".toByteArray(Charsets.UTF_8)
        SyncMessageCodec.deserialize(malformed)
    }

    @Test
    fun testObservationEntityEvidenceExtensions() {
        val obsWithoutEvidence = ObservationEntity(
            eventId = "evt-100",
            missionId = "m1",
            authorDeviceId = "dev-1",
            authorSequence = 1L,
            eventType = "NOTE",
            title = "Simple observation",
            observedAtDevice = 1000L
        )

        assertNull(obsWithoutEvidence.evidenceSha256)
        assertNull(obsWithoutEvidence.evidenceFilePath)
        assertEquals(0L, obsWithoutEvidence.evidenceSizeBytes)
        assertEquals("image/jpeg", obsWithoutEvidence.evidenceMimeType)
        assertFalse(obsWithoutEvidence.hasEvidence)

        val tempFile = File.createTempFile("evidence_test_", ".jpg").apply {
            writeBytes(ByteArray(1024))
            deleteOnExit()
        }

        val obsWithEvidence = obsWithoutEvidence.copy(
            evidencePath = tempFile.absolutePath,
            evidenceHash = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
            verificationStatus = "VERIFIED"
        )

        assertEquals("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", obsWithEvidence.evidenceSha256)
        assertEquals(tempFile.absolutePath, obsWithEvidence.evidenceFilePath)
        assertEquals(1024L, obsWithEvidence.evidenceSizeBytes)
        assertEquals("image/jpeg", obsWithEvidence.evidenceMimeType)
        assertTrue(obsWithEvidence.hasEvidence)
    }

    @Test
    fun testObservationDaoUpdateEvidence() = runBlocking {
        val dao = FakeObservationDao()
        val obs = ObservationEntity(
            eventId = "evt-200",
            missionId = "m1",
            authorDeviceId = "dev-1",
            authorSequence = 1L,
            eventType = "PHOTO",
            title = "Initial observation without photo",
            observedAtDevice = 1000L,
            evidencePath = null,
            verificationStatus = "UNVERIFIED"
        )
        dao.insert(obs)

        val fetchedInitial = dao.getById("evt-200")
        assertNotNull(fetchedInitial)
        assertNull(fetchedInitial!!.evidencePath)
        assertEquals("UNVERIFIED", fetchedInitial.verificationStatus)

        dao.updateEvidence("evt-200", "/data/app/evidence/evt-200.jpg", "VERIFIED")

        val fetchedUpdated = dao.getById("evt-200")
        assertNotNull(fetchedUpdated)
        assertEquals("/data/app/evidence/evt-200.jpg", fetchedUpdated!!.evidencePath)
        assertEquals("VERIFIED", fetchedUpdated.verificationStatus)
    }
}
