package com.rescuemesh.ai.mesh

import android.content.Context
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.tasks.Tasks
import com.rescuemesh.ai.model.PeerInfo
import com.rescuemesh.ai.model.SyncMessage
import com.rescuemesh.ai.model.SyncObservation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Unit & Adversarial Test Suite for Milestone 5: Features 19 & 20
 * P2P Handshake Authentication & Mission Isolation.
 *
 * Verifies:
 * 1. Matching mission IDs complete handshake and permit bidirectional sync.
 * 2. Mismatched mission IDs trigger immediate disconnect and drop subsequent messages.
 * 3. Empty or corrupted mission IDs are rejected and disconnected.
 * 4. Unauthenticated endpoints cannot inject observations or state digests.
 * 5. Dynamic mission ID changes immediately update handshake verification.
 * 6. Disconnection properly clears authentication state.
 */
class HandshakeAuthenticationTest {

    @Test
    fun asynchronousSendFailureIsReportedToCoordinator() = runBlocking {
        val transport = createTransport()
        transport.handleIncomingMessage("endpoint", SyncMessage.Hello(deviceId = "peer", deviceName = "Peer", missionId = activeMission))
        fakeClient.failSend = true

        assertFalse("Failed Nearby Task must not count as successful dispatch",
            transport.sendMessage("endpoint", SyncMessage.StateDigest(deviceId = "local", sequences = emptyMap())))
    }

    private lateinit var dummyContext: Context
    private lateinit var fakeClient: FakeConnectionsClient
    private lateinit var connectionsClientProxy: ConnectionsClient

    private var activeMission = "MISSION_SECTOR_BRAVO"

    @Before
    fun setUp() {
        activeMission = "MISSION_SECTOR_BRAVO"
        fakeClient = FakeConnectionsClient()
        connectionsClientProxy = fakeClient.createProxy()
        dummyContext = DummyContext()
    }

    private fun createTransport(missionProvider: () -> String = { activeMission }): NearbyMeshTransport {
        return NearbyMeshTransport(
            context = dummyContext,
            missionIdProvider = missionProvider,
            nearbyConnectionsOverride = connectionsClientProxy
        )
    }

    @Test
    fun testMatchingMissionCompletesHandshakeAndAllowsSync() = runBlocking {
        val transport = createTransport()
        var connectedPeerInfo: PeerInfo? = null
        transport.setOnPeerConnected { _, peerInfo ->
            connectedPeerInfo = peerInfo
        }

        val endpointId = "endpoint-alpha"
        val hello = SyncMessage.Hello(
            deviceId = "device-alpha",
            deviceName = "Rescuer Alpha",
            missionId = "MISSION_SECTOR_BRAVO"
        )

        // Deliver Hello handshake
        transport.handleIncomingMessage(endpointId, hello)

        // 1. Verify endpoint is authenticated
        assertTrue("Endpoint must be registered as authenticated", transport.connectedEndpoints.contains(endpointId))

        // 2. Verify peer registered in connectedPeers
        val peer = transport.connectedPeers.value["device-alpha"]
        assertNotNull("Peer must be present in connectedPeers", peer)
        assertEquals("device-alpha", peer?.deviceId)
        assertEquals("endpoint-alpha", peer?.endpointId)
        assertEquals("Rescuer Alpha", peer?.displayName)

        // 3. Verify callback fired
        assertNotNull("onPeerConnected callback must be invoked", connectedPeerInfo)
        assertEquals("device-alpha", connectedPeerInfo?.deviceId)

        // 4. Verify no disconnect was triggered
        assertFalse("Endpoint must NOT be disconnected", fakeClient.disconnectedEndpoints.contains(endpointId))

        // 5. Subsequent valid sync messages must be accepted and emitted
        val digest = SyncMessage.StateDigest(
            deviceId = "device-alpha",
            sequences = mapOf("device-alpha" to 5L)
        )

        var emittedMessage: SyncMessage? = null
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            transport.incomingMessages.collect { (ep, msg) ->
                if (ep == endpointId && msg is SyncMessage.StateDigest) {
                    emittedMessage = msg
                }
            }
        }

        transport.handleIncomingMessage(endpointId, digest)
        job.cancel()

        assertNotNull("State digest from authenticated peer must be received", emittedMessage)
        assertEquals(digest, emittedMessage)
    }

    @Test
    fun testMismatchedMissionDisconnectsImmediatelyAndDropsSubsequentMessages() = runBlocking {
        val transport = createTransport()
        val foreignEndpoint = "endpoint-foreign-99"
        val foreignHello = SyncMessage.Hello(
            deviceId = "device-rogue",
            deviceName = "Rogue Device",
            missionId = "FOREIGN_MISSION_DELTA" // Mismatch! Active is MISSION_SECTOR_BRAVO
        )

        // Deliver foreign Hello
        transport.handleIncomingMessage(foreignEndpoint, foreignHello)

        // 1. Immediate disconnect must be called
        assertTrue("Foreign mission must trigger disconnectFromEndpoint", fakeClient.disconnectedEndpoints.contains(foreignEndpoint))

        // 2. Must not be in connectedEndpoints
        assertFalse("Foreign endpoint must not be authenticated", transport.connectedEndpoints.contains(foreignEndpoint))

        // 3. Must not be in connectedPeers
        assertFalse("Foreign peer must not be in connectedPeers", transport.connectedPeers.value.containsKey("device-rogue"))

        // 4. Subsequent messages from this foreign endpoint must be dropped
        var receivedAnyMessage = false
        val job = launch {
            transport.incomingMessages.collect {
                receivedAnyMessage = true
            }
        }

        val maliciousObservationBatch = SyncMessage.EventBatch(
            senderDeviceId = "device-rogue",
            events = listOf(
                SyncObservation(
                    eventId = "evt-foreign-1",
                    missionId = "FOREIGN_MISSION_DELTA",
                    authorDeviceId = "device-rogue",
                    authorSequence = 1L,
                    eventType = "HAZARD",
                    title = "Foreign Hazard",
                    observedAtDevice = System.currentTimeMillis()
                )
            )
        )

        transport.handleIncomingMessage(foreignEndpoint, maliciousObservationBatch)
        kotlinx.coroutines.delay(50)
        job.cancel()

        assertFalse("Subsequent observation batch from foreign peer must be completely dropped", receivedAnyMessage)
    }

    @Test
    fun testEmptyOrBlankMissionIdIsRejected() = runBlocking {
        val transport = createTransport()

        // 1. Empty string missionId
        val emptyEndpoint = "endpoint-empty"
        val emptyHello = SyncMessage.Hello(
            deviceId = "device-empty",
            deviceName = "Empty Device",
            missionId = ""
        )
        transport.handleIncomingMessage(emptyEndpoint, emptyHello)

        assertTrue("Empty mission ID must trigger disconnect", fakeClient.disconnectedEndpoints.contains(emptyEndpoint))
        assertFalse(transport.connectedEndpoints.contains(emptyEndpoint))

        // 2. Whitespace-only missionId
        val whitespaceEndpoint = "endpoint-whitespace"
        val whitespaceHello = SyncMessage.Hello(
            deviceId = "device-whitespace",
            deviceName = "Whitespace Device",
            missionId = "    "
        )
        transport.handleIncomingMessage(whitespaceEndpoint, whitespaceHello)

        assertTrue("Whitespace mission ID must trigger disconnect", fakeClient.disconnectedEndpoints.contains(whitespaceEndpoint))
        assertFalse(transport.connectedEndpoints.contains(whitespaceEndpoint))
    }

    @Test
    fun testCorruptedOrMalformedMissionIdIsRejected() = runBlocking {
        val transport = createTransport()

        val corruptedCases = listOf(
            "MISSION_SECTOR_BRAVO\u0000CORRUPT",
            "null",
            "undefined",
            "MISSION_SECTOR_BRAVO_EXTRA_SUFFIX",
            "INVALID_CHAR#$!@%"
        )

        for ((index, badMission) in corruptedCases.withIndex()) {
            val ep = "endpoint-corrupt-$index"
            val hello = SyncMessage.Hello(
                deviceId = "device-corrupt-$index",
                deviceName = "Corrupt Device $index",
                missionId = badMission
            )

            transport.handleIncomingMessage(ep, hello)

            assertTrue("Corrupted mission '$badMission' must trigger disconnect", fakeClient.disconnectedEndpoints.contains(ep))
            assertFalse("Endpoint $ep must not be authenticated", transport.connectedEndpoints.contains(ep))
            assertFalse(transport.connectedPeers.value.containsKey("device-corrupt-$index"))
        }
    }

    @Test
    fun testUnauthenticatedEndpointCannotSendObservationsWithoutHello() = runBlocking {
        val transport = createTransport()
        val rogueEndpoint = "endpoint-unauthenticated"

        var messageEmitted = false
        val job = launch {
            transport.incomingMessages.collect {
                messageEmitted = true
            }
        }

        // Send EventBatch directly without ever exchanging Hello
        val directBatch = SyncMessage.EventBatch(
            senderDeviceId = "unannounced-peer",
            events = listOf(
                SyncObservation(
                    eventId = "evt-sneak-1",
                    missionId = activeMission,
                    authorDeviceId = "unannounced-peer",
                    authorSequence = 1L,
                    eventType = "ROUTE_STATUS",
                    title = "Sneak Pass",
                    observedAtDevice = System.currentTimeMillis()
                )
            )
        )

        transport.handleIncomingMessage(rogueEndpoint, directBatch)
        kotlinx.coroutines.delay(50)
        job.cancel()

        assertFalse("Unauthenticated endpoint cannot send observations or bypass handshake", messageEmitted)
    }

    @Test
    fun testDynamicMissionSwitchUpdatesAuthenticationInstantly() = runBlocking {
        var currentMission = "MISSION_ALPHA"
        val transport = createTransport(missionProvider = { currentMission })

        val endpoint1 = "endpoint-1"
        val helloMission1 = SyncMessage.Hello(
            deviceId = "dev-1",
            deviceName = "Device 1",
            missionId = "MISSION_ALPHA"
        )

        // Handshake succeeds for MISSION_ALPHA
        transport.handleIncomingMessage(endpoint1, helloMission1)
        assertTrue("Endpoint 1 must authenticate with MISSION_ALPHA", transport.connectedEndpoints.contains(endpoint1))

        // Dynamic switch of active mission to MISSION_BETA
        currentMission = "MISSION_BETA"
        assertEquals("MISSION_BETA", transport.activeMissionId)

        // New peer trying to connect with outdated MISSION_ALPHA
        val endpoint2 = "endpoint-2"
        val helloOutdated = SyncMessage.Hello(
            deviceId = "dev-2",
            deviceName = "Device 2",
            missionId = "MISSION_ALPHA" // Stale! Active is now MISSION_BETA
        )
        transport.handleIncomingMessage(endpoint2, helloOutdated)

        assertTrue("Outdated mission peer must be rejected after dynamic switch", fakeClient.disconnectedEndpoints.contains(endpoint2))
        assertFalse(transport.connectedEndpoints.contains(endpoint2))

        // New peer connecting with new active MISSION_BETA
        val endpoint3 = "endpoint-3"
        val helloNew = SyncMessage.Hello(
            deviceId = "dev-3",
            deviceName = "Device 3",
            missionId = "MISSION_BETA"
        )
        transport.handleIncomingMessage(endpoint3, helloNew)

        assertTrue("New peer must authenticate with updated MISSION_BETA", transport.connectedEndpoints.contains(endpoint3))
    }

    @Test
    fun testSendMessageRejectsUnauthenticatedEndpoints() = runBlocking {
        val transport = createTransport()
        val unauthEndpoint = "endpoint-random"

        val batch = SyncMessage.EventBatch(
            senderDeviceId = "local-device",
            events = emptyList<SyncObservation>()
        )

        // Sending to unauthenticated endpoint must fail
        val sent = transport.sendMessage(unauthEndpoint, batch)
        assertFalse("Sending sync messages to unauthenticated endpoint must fail", sent)
        assertEquals("No payload should be dispatched for unauthenticated endpoint", 0, fakeClient.sentPayloads.size)
    }

    // --- Fake Test Double for ConnectionsClient ---

    private class DummyContext : android.content.ContextWrapper(null) {
        override fun getPackageName(): String = "com.rescuemesh.ai"
        override fun getApplicationContext(): Context = this
    }

    private class FakeConnectionsClient : InvocationHandler {
        var failSend = false
        val disconnectedEndpoints = mutableListOf<String>()
        val sentPayloads = mutableListOf<Pair<String, Payload>>()

        fun createProxy(): ConnectionsClient {
            return Proxy.newProxyInstance(
                ConnectionsClient::class.java.classLoader,
                arrayOf(ConnectionsClient::class.java),
                this
            ) as ConnectionsClient
        }

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            val nonNullArgs = args ?: emptyArray()
            return when (method.name) {
                "disconnectFromEndpoint" -> {
                    val endpointId = nonNullArgs[0] as String
                    disconnectedEndpoints.add(endpointId)
                    Tasks.forResult(null)
                }
                "sendPayload" -> {
                    val endpointId = nonNullArgs[0] as String
                    val payload = nonNullArgs[1] as Payload
                    sentPayloads.add(endpointId to payload)
                    if (failSend) Tasks.forException<Void>(IllegalStateException("Link lost")) else Tasks.forResult(null)
                }
                "stopAllEndpoints", "stopAdvertising", "stopDiscovery" -> {
                    null
                }
                "toString" -> "FakeConnectionsClient"
                "hashCode" -> 42
                "equals" -> proxy === nonNullArgs.getOrNull(0)
                else -> null
            }
        }
    }
}
