package com.rescuemesh.ai.model

import kotlinx.serialization.SerializationException
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/**
 * Empirical stress harness challenging [SyncMessageCodec] against:
 * 1. All five message types with empty collections and boundary values
 * 2. Giant payloads (1,000+ observations, 100KB text blobs)
 * 3. Special characters (quotes, backslashes, newlines, JSON delimiters, multi-byte UTF-8, emojis, non-Latin scripts)
 * 4. Byte size accounting exactness (calculatePayloadBytes vs serialize().size)
 * 5. Corrupt, truncated, and malformed inputs
 */
class SyncMessageCodecAdversarialTest {

    @Test
    fun testAllFiveMessageTypesEmptyCollectionsRoundTrip() {
        // 1. Hello with empty strings
        val hello = SyncMessage.Hello(
            deviceId = "",
            deviceName = "",
            missionId = "",
            protocolVersion = 0
        )
        assertRoundTrip(hello)

        // 2. StateDigest with empty map
        val digest = SyncMessage.StateDigest(
            deviceId = "dev-empty",
            sequences = emptyMap()
        )
        assertRoundTrip(digest)

        // 3. EventRequest with empty map
        val request = SyncMessage.EventRequest(
            requestorDeviceId = "dev-empty",
            missingFromAuthors = emptyMap()
        )
        assertRoundTrip(request)

        // 4. EventBatch with empty list
        val batch = SyncMessage.EventBatch(
            senderDeviceId = "dev-empty",
            events = emptyList()
        )
        assertRoundTrip(batch)

        // 5. EventAck with empty list
        val ack = SyncMessage.EventAck(
            deviceId = "dev-empty",
            acknowledgedEventIds = emptyList()
        )
        assertRoundTrip(ack)
    }

    @Test
    fun testGiantEventPayloadRoundTripOneThousandObservations() {
        val count = 1000
        val observations = (1..count).map { i ->
            SyncObservation(
                eventId = "evt-giant-$i",
                missionId = "mission-stress",
                authorDeviceId = "device-${i % 10}",
                authorSequence = i.toLong(),
                eventType = if (i % 2 == 0) "HAZARD" else "ROUTE_STATUS",
                title = "Massive stress observation #$i with details",
                description = "Automated generated description for observation $i under heavy load testing.",
                reportedStatus = if (i % 2 == 0) "BLOCKED" else "OPEN",
                sourceType = "SIMULATION",
                latitude = 37.0 + (i % 100) * 0.001,
                longitude = -122.0 - (i % 100) * 0.001,
                accuracy = 1.5f + (i % 5),
                mapEdgeId = "edge-${i % 20}",
                mapNodeId = "node-${i % 50}",
                verificationStatus = if (i % 3 == 0) "VERIFIED" else "UNVERIFIED",
                observedAtDevice = 1700000000000L + i,
                relayHopsRemaining = i % 4
            )
        }

        val batch = SyncMessage.EventBatch(
            senderDeviceId = "sender-node-stress",
            events = observations
        )

        val serialized = SyncMessageCodec.serialize(batch)
        assertTrue("Batch with 1000 observations should be substantial (>100KB)", serialized.size > 100_000)

        // Exact byte calculation match
        val calculatedBytes = SyncMessageCodec.calculatePayloadBytes(batch)
        assertEquals("Byte calculation must exactly equal serialized size", serialized.size, calculatedBytes)

        val restored = SyncMessageCodec.deserialize(serialized) as SyncMessage.EventBatch
        assertEquals(batch.senderDeviceId, restored.senderDeviceId)
        assertEquals(count, restored.events.size)
        assertEquals(batch.events.first().eventId, restored.events.first().eventId)
        assertEquals(batch.events.last().eventId, restored.events.last().eventId)
        assertEquals(batch.events[500].latitude!!, restored.events[500].latitude!!, 1e-6)
    }

    @Test
    fun testGiantStringPayloadOneHundredKilobytes() {
        val largeTextBuilder = java.lang.StringBuilder()
        while (largeTextBuilder.length < 100_000) {
            largeTextBuilder.append("Critical disaster recon log: structural integrity compromised at sector 7G. ")
        }
        val giantDescription = largeTextBuilder.toString()

        val obs = SyncObservation(
            eventId = "evt-giant-text",
            missionId = "m-large",
            authorDeviceId = "node-alpha",
            authorSequence = 9999999L,
            eventType = "HAZARD",
            title = "Extremely Long Field Report",
            description = giantDescription,
            observedAtDevice = 1700000000000L
        )

        val batch = SyncMessage.EventBatch(
            senderDeviceId = "node-alpha",
            events = listOf(obs)
        )

        val serialized = SyncMessageCodec.serialize(batch)
        assertTrue(serialized.size >= 100_000)
        assertEquals(serialized.size, SyncMessageCodec.calculatePayloadBytes(batch))

        val restored = SyncMessageCodec.deserialize(serialized) as SyncMessage.EventBatch
        assertEquals(1, restored.events.size)
        assertEquals(giantDescription, restored.events[0].description)
    }

    @Test
    fun testSpecialCharactersAndEscapingStress() {
        val complexTitle = """Path "A/B" \ [danger]: {key: "value"}, newline: 
and tab:	"""
        val complexDesc = """
            Line 1: Special symbols: ~!@#$%^&*()_+`-={}|[]\:";'<>?,./
            Line 2: Emojis: 🚨⚠️🆘🔥🌊🧑‍🚒📡
            Line 3: Multilingual:
              Arabic: زلزال عنيف في المنطقة الشمالية
              Japanese: 地震による道路崩落・救助要請
              Russian: Срочная эвакуация пострадавших
              Hindi: भूकंप के कारण मार्ग अवरुद्ध
            Line 4: Control characters: \u0001 \u001F \u200B
        """.trimIndent()

        val obs = SyncObservation(
            eventId = "evt-escape-test",
            missionId = "mission-complex",
            authorDeviceId = "device-\"special\"-\\1",
            authorSequence = 12345L,
            eventType = "HAZARD",
            title = complexTitle,
            description = complexDesc,
            reportedStatus = "BLOCKED",
            mapEdgeId = "edge-\"quoted\"-1",
            observedAtDevice = 1700000000000L
        )

        val batch = SyncMessage.EventBatch(
            senderDeviceId = "device-\"special\"-\\1",
            events = listOf(obs)
        )

        assertRoundTrip(batch)

        val bytes = SyncMessageCodec.serialize(batch)
        val restored = SyncMessageCodec.deserialize(bytes) as SyncMessage.EventBatch
        assertEquals(complexTitle, restored.events[0].title)
        assertEquals(complexDesc, restored.events[0].description)
        assertEquals("edge-\"quoted\"-1", restored.events[0].mapEdgeId)
        assertEquals("device-\"special\"-\\1", restored.events[0].authorDeviceId)
    }

    @Test
    fun testBoundaryNumericValuesAndNullability() {
        val obs = SyncObservation(
            eventId = "evt-boundary",
            missionId = "m-boundary",
            authorDeviceId = "dev-max",
            authorSequence = Long.MAX_VALUE,
            eventType = "NOTE",
            title = "Boundary values",
            description = "",
            reportedStatus = null,
            latitude = -89.9999999,
            longitude = 179.9999999,
            accuracy = 0.00001f,
            mapEdgeId = null,
            mapNodeId = null,
            observedAtDevice = Long.MAX_VALUE - 1,
            relayHopsRemaining = 0
        )

        val batch = SyncMessage.EventBatch(senderDeviceId = "dev-max", events = listOf(obs))
        assertRoundTrip(batch)

        val restored = (SyncMessageCodec.deserialize(SyncMessageCodec.serialize(batch)) as SyncMessage.EventBatch).events[0]
        assertEquals(Long.MAX_VALUE, restored.authorSequence)
        assertEquals(Long.MAX_VALUE - 1, restored.observedAtDevice)
        assertNull(restored.reportedStatus)
        assertNull(restored.mapEdgeId)
        assertNull(restored.mapNodeId)
        assertEquals(0, restored.relayHopsRemaining)
        assertEquals(-89.9999999, restored.latitude!!, 1e-7)
        assertEquals(179.9999999, restored.longitude!!, 1e-7)
        assertEquals(0.00001f, restored.accuracy!!, 1e-7f)
    }

    @Test
    fun testStateDigestWithThousandsOfSequences() {
        val sequences = (1..2000).associate { i -> "device-node-$i" to (i * 10L) }
        val digest = SyncMessage.StateDigest(
            deviceId = "hub-device",
            sequences = sequences
        )

        assertRoundTrip(digest)
        val restored = SyncMessageCodec.deserialize(SyncMessageCodec.serialize(digest)) as SyncMessage.StateDigest
        assertEquals(2000, restored.sequences.size)
        assertEquals(10L, restored.sequences["device-node-1"])
        assertEquals(20000L, restored.sequences["device-node-2000"])
    }

    @Test
    fun testEventRequestWithThousandsOfAuthors() {
        val missing = (1..1500).associate { i -> "author-$i" to (i * 5L) }
        val request = SyncMessage.EventRequest(
            requestorDeviceId = "requestor-1",
            missingFromAuthors = missing
        )

        assertRoundTrip(request)
        val restored = SyncMessageCodec.deserialize(SyncMessageCodec.serialize(request)) as SyncMessage.EventRequest
        assertEquals(1500, restored.missingFromAuthors.size)
        assertEquals(5L, restored.missingFromAuthors["author-1"])
    }

    @Test
    fun testEventAckWithThousandsOfEventIds() {
        val ackIds = (1..3000).map { "evt-ack-uuid-$it" }
        val ack = SyncMessage.EventAck(
            deviceId = "ack-device",
            acknowledgedEventIds = ackIds
        )

        assertRoundTrip(ack)
        val restored = SyncMessageCodec.deserialize(SyncMessageCodec.serialize(ack)) as SyncMessage.EventAck
        assertEquals(3000, restored.acknowledgedEventIds.size)
        assertEquals("evt-ack-uuid-1", restored.acknowledgedEventIds.first())
        assertEquals("evt-ack-uuid-3000", restored.acknowledgedEventIds.last())
    }

    @Test(expected = SerializationException::class)
    fun testCorruptPayloadInsideEnvelopeThrowsSerializationException() {
        // Valid outer JSON envelope, but inner payload is corrupted JSON for HELLO
        val corruptPayloadEnvelope = """{"type":"HELLO","payload":"{invalid-json-content:"}"""
        SyncMessageCodec.deserializeFromString(corruptPayloadEnvelope)
    }

    @Test(expected = SerializationException::class)
    fun testEmptyPayloadInsideEnvelopeThrowsSerializationException() {
        val emptyPayloadEnvelope = """{"type":"HELLO","payload":""}"""
        SyncMessageCodec.deserializeFromString(emptyPayloadEnvelope)
    }

    @Test(expected = SerializationException::class)
    fun testTruncatedBytesThrowsSerializationException() {
        val hello = SyncMessage.Hello(deviceId = "dev-1", deviceName = "Dev", missionId = "m1")
        val bytes = SyncMessageCodec.serialize(hello)
        // Truncate halfway
        val truncated = bytes.copyOf(bytes.size / 2)
        SyncMessageCodec.deserialize(truncated)
    }

    @Test(expected = SerializationException::class)
    fun testRandomBytesThrowsSerializationException() {
        val randomBytes = ByteArray(128)
        Random(42).nextBytes(randomBytes)
        SyncMessageCodec.deserialize(randomBytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCaseSensitiveUnknownEnvelopeTypeThrows() {
        val lowerCaseEnvelope = """{"type":"hello","payload":"{}"}"""
        SyncMessageCodec.deserializeFromString(lowerCaseEnvelope)
    }

    @Test
    fun testCalculatedPayloadBytesPropertyInvarianceAcrossFuzzedInputs() {
        val random = Random(12345)
        for (round in 1..50) {
            val msg: SyncMessage = when (round % 5) {
                0 -> SyncMessage.Hello(
                    deviceId = "dev-$round",
                    deviceName = "Device name with random unicode ${random.nextInt()}",
                    missionId = "m-${random.nextLong()}"
                )
                1 -> SyncMessage.StateDigest(
                    deviceId = "dev-$round",
                    sequences = (1..(round % 10 + 1)).associate { "auth-$it" to random.nextLong() }
                )
                2 -> SyncMessage.EventRequest(
                    requestorDeviceId = "dev-$round",
                    missingFromAuthors = (1..(round % 10 + 1)).associate { "auth-$it" to random.nextLong() }
                )
                3 -> SyncMessage.EventAck(
                    deviceId = "dev-$round",
                    acknowledgedEventIds = (1..(round % 10 + 1)).map { "evt-$it-${random.nextInt()}" }
                )
                else -> SyncMessage.EventBatch(
                    senderDeviceId = "dev-$round",
                    events = (1..(round % 5 + 1)).map { i ->
                        SyncObservation(
                            eventId = "fuzz-evt-$round-$i",
                            missionId = "m-$round",
                            authorDeviceId = "auth-$i",
                            authorSequence = i.toLong(),
                            eventType = "NOTE",
                            title = "Fuzz title $i [${random.nextGaussian()}]",
                            description = "Random description ${random.nextDouble()}",
                            observedAtDevice = System.currentTimeMillis()
                        )
                    }
                )
            }
            val calculated = SyncMessageCodec.calculatePayloadBytes(msg)
            val actual = SyncMessageCodec.serialize(msg).size
            assertEquals("Fuzz round $round: calculated bytes must match serialized length", actual, calculated)
        }
    }

    private fun assertRoundTrip(message: SyncMessage) {
        val bytes = SyncMessageCodec.serialize(message)
        val calculatedBytes = SyncMessageCodec.calculatePayloadBytes(message)
        assertEquals("Calculated bytes must match actual serialized byte length", bytes.size, calculatedBytes)

        val restored = SyncMessageCodec.deserialize(bytes)
        assertEquals("Restored message type must match", message.type, restored.type)
        assertEquals("Restored message must equal original", message, restored)
    }
}
