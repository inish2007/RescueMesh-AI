package com.rescuemesh.ai.simulation

import com.rescuemesh.ai.mesh.MeshState
import com.rescuemesh.ai.model.SyncMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit and integration tests for modular simulation package:
 * - VirtualMeshTransport
 * - VirtualMeshEnvironment
 * - SimulatedRescueNode
 */
class VirtualMeshSimulationTest {

    private lateinit var environment: VirtualMeshEnvironment

    @Before
    fun setUp() {
        environment = VirtualMeshEnvironment(latencyMs = 0L, packetLossRate = 0.0)
    }

    @Test
    fun testVirtualMeshTransportLifecycleAndPeerTracking() = runBlocking {
        val transportA = VirtualMeshTransport("node-A", "Device A", environment)
        val transportB = VirtualMeshTransport("node-B", "Device B", environment)

        assertEquals(MeshState.STOPPED, transportA.meshState.value)
        transportA.startAdvertisingAndDiscovery("Device A", "ROLE_RESCUE")
        assertEquals(MeshState.ACTIVE, transportA.meshState.value)

        var peerConnectedCalled = false
        transportA.setOnPeerConnected { deviceId, peerInfo ->
            if (deviceId == "node-B") peerConnectedCalled = true
        }

        // Connect nodes in environment
        environment.connectNodes("node-A", "node-B")
        assertTrue(peerConnectedCalled)
        assertTrue(transportA.connectedPeers.value.containsKey("node-B"))

        // Send message from A to B
        val msg = SyncMessage.Hello(deviceId = "node-A", deviceName = "Device A", missionId = "test-mission")
        val sent = transportA.sendMessage("endpoint-node-B", msg)
        assertTrue(sent)
        assertTrue(transportA.getBytesSent() > 0)

        // Disconnect
        var peerDisconnectedCalled = false
        transportA.setOnPeerDisconnected { deviceId ->
            if (deviceId == "node-B") peerDisconnectedCalled = true
        }
        environment.disconnectNodes("node-A", "node-B")
        assertTrue(peerDisconnectedCalled)
        assertFalse(transportA.connectedPeers.value.containsKey("node-B"))

        transportA.stop()
        assertEquals(MeshState.STOPPED, transportA.meshState.value)
    }

    @Test
    fun testVirtualMeshEnvironmentPartitionAndHeal() = runBlocking {
        val node1 = environment.registerNode("n1")
        val node2 = environment.registerNode("n2")
        val node3 = environment.registerNode("n3")

        assertTrue(environment.canCommunicate("n1", "n2"))
        assertTrue(environment.canCommunicate("n1", "n3"))

        // Partition {n1} vs {n2, n3}
        environment.partitionMesh(setOf("n1"), setOf("n2", "n3"))
        assertFalse(environment.canCommunicate("n1", "n2"))
        assertFalse(environment.canCommunicate("n1", "n3"))
        assertTrue(environment.canCommunicate("n2", "n3"))

        // Message across partition fails
        val msg = SyncMessage.Hello(deviceId = "n1", deviceName = "N1", missionId = "test-mission")
        val sent = environment.deliverMessage("n1", "n2", msg)
        assertFalse(sent)
        assertTrue(environment.totalPacketsDropped.get() > 0)

        // Heal
        environment.healMesh()
        assertTrue(environment.canCommunicate("n1", "n2"))
        val sentAfterHeal = environment.deliverMessage("n1", "n2", msg)
        assertTrue(sentAfterHeal)
    }

    @Test
    fun testVirtualMeshEnvironmentBurstDeliveries() = runBlocking {
        val node1 = environment.registerNode("b1")
        val node2 = environment.registerNode("b2")
        val node3 = environment.registerNode("b3")

        val burst: List<Triple<String, String, SyncMessage>> = listOf(
            Triple("b1", "b2", SyncMessage.Hello(deviceId = "b1", deviceName = "B1", missionId = "test-mission")),
            Triple("b2", "b3", SyncMessage.Hello(deviceId = "b2", deviceName = "B2", missionId = "test-mission")),
            Triple("b3", "b1", SyncMessage.Hello(deviceId = "b3", deviceName = "B3", missionId = "test-mission"))
        )

        val results = environment.deliverBurst(burst)
        assertEquals(3, results.size)
        assertTrue(results.all { it })
    }

    @Test
    fun testSimulatedRescueNodeEncapsulationAndPersistence() = runBlocking {
        val node = environment.registerNode("node-alpha")

        assertNotNull(node.observationDao)
        assertNotNull(node.mapDao)
        assertNotNull(node.outboxDao)
        assertNotNull(node.metricsDao)
        assertNotNull(node.peerDao)
        assertNotNull(node.transport)
        assertNotNull(node.syncCoordinator)

        val obs = node.createObservation(
            eventType = "HAZARD",
            title = "Gas Leak Hazard",
            description = "Hazardous vapor detected"
        )

        // Durable persistence check
        assertTrue(node.observationDao.exists(obs.eventId))
        val pendingOutbox = node.outboxDao.getPendingByFifo()
        assertEquals(1, pendingOutbox.size)
        assertEquals(obs.eventId, pendingOutbox[0].eventId)
        assertTrue(pendingOutbox[0].priority >= 0.80) // Invariant 5 safety floor
    }
}
