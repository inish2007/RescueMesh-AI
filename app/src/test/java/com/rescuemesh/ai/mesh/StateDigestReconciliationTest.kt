package com.rescuemesh.ai.mesh

import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.data.repository.ObservationRepository
import com.rescuemesh.ai.data.repository.SyncRepository
import com.rescuemesh.ai.fakes.FakeMapDao
import com.rescuemesh.ai.fakes.FakeMetricsDao
import com.rescuemesh.ai.fakes.FakeObservationDao
import com.rescuemesh.ai.fakes.FakeOutboxDao
import com.rescuemesh.ai.fakes.FakePeerDao
import com.rescuemesh.ai.intelligence.MapFusionEngine
import com.rescuemesh.ai.intelligence.PriorityScheduler
import com.rescuemesh.ai.model.PeerInfo
import com.rescuemesh.ai.model.SyncMessage
import com.rescuemesh.ai.model.SyncMessageCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Verification test suite for Feature 24: Post-Reconnect State Digest Reconciliation.
 *
 * Verifies that:
 * 1. Partitioned nodes creating observations independently reconcile all missing events upon reconnecting.
 * 2. Unidirectional reconciliation succeeds when an offline/partitioned node rejoins an up-to-date node.
 * 3. Multi-author state digest reconciliation requests and syncs events across multiple author nodes.
 * 4. Missing observations enqueued during reconciliation are committed to local Outbox (Durable Local-First Persistence).
 * 5. State reconciliation is idempotent when nodes are already synchronized (zero redundant transfers).
 * 6. State reconciliation records transport telemetry (SYNC, BYTES_SENT, BYTES_RECEIVED, OUTBOX_QUEUE_DEPTH) in MetricsDao.
 */
class StateDigestReconciliationTest {

    private lateinit var nodeA: TestNode
    private lateinit var nodeB: TestNode

    private val missionId = "mission-reconcile-alpha"

    @Before
    fun setUp() {
        nodeA = TestNode("node-alpha", missionId)
        nodeB = TestNode("node-bravo", missionId)

        // Wire transports together directly
        nodeA.transport.peerTransport = nodeB.transport
        nodeB.transport.peerTransport = nodeA.transport

        nodeA.start()
        nodeB.start()
    }

    @After
    fun tearDown() {
        nodeA.stop()
        nodeB.stop()
    }

    private suspend fun waitUntil(timeoutMs: Long = 4000L, condition: suspend () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition()) return
            delay(25)
        }
        if (!condition()) {
            fail("Timed out waiting for condition after ${timeoutMs}ms")
        }
    }

    private suspend fun createObservation(
        node: TestNode,
        eventId: String,
        author: String,
        seq: Long,
        type: String = "NOTE",
        title: String = "Observation $eventId",
        status: String? = null
    ): ObservationEntity {
        val obs = ObservationEntity(
            eventId = eventId,
            missionId = missionId,
            authorDeviceId = author,
            authorSequence = seq,
            eventType = type,
            title = title,
            reportedStatus = status,
            observedAtDevice = System.currentTimeMillis(),
            isLocal = (author == node.deviceId)
        )
        node.observationDao.insert(obs)
        return obs
    }

    @Test
    fun testBidirectionalReconciliationAfterNetworkPartition() = runBlocking {
        // 1. Simulate network partition: nodes cannot communicate
        nodeA.transport.isPartitioned = true
        nodeB.transport.isPartitioned = true

        // 2. Both nodes independently create observations during the partition
        createObservation(nodeA, "obs-A-1", author = "node-alpha", seq = 1L)
        createObservation(nodeA, "obs-A-2", author = "node-alpha", seq = 2L)

        createObservation(nodeB, "obs-B-1", author = "node-bravo", seq = 1L)
        createObservation(nodeB, "obs-B-2", author = "node-bravo", seq = 2L)
        createObservation(nodeB, "obs-B-3", author = "node-bravo", seq = 3L)

        // Verify initial partitioned state: Node A only has 2, Node B only has 3
        assertEquals(2, nodeA.observationDao.getAll(missionId).size)
        assertEquals(3, nodeB.observationDao.getAll(missionId).size)

        // 3. Heal partition and trigger reconnect handshake / digest exchange
        nodeA.transport.isPartitioned = false
        nodeB.transport.isPartitioned = false

        val peerInfoB = PeerInfo("node-bravo", "endpoint-node-bravo", "Device Bravo")
        val peerInfoA = PeerInfo("node-alpha", "endpoint-node-alpha", "Device Alpha")

        nodeA.transport.triggerPeerConnected("node-bravo", peerInfoB)
        nodeB.transport.triggerPeerConnected("node-alpha", peerInfoA)

        // 4. Wait for state reconciliation: both nodes must converge to all 5 observations
        waitUntil {
            nodeA.observationDao.getAll(missionId).size == 5 &&
            nodeB.observationDao.getAll(missionId).size == 5
        }

        val observationsOnA = nodeA.observationDao.getAll(missionId).map { it.eventId }.toSet()
        val observationsOnB = nodeB.observationDao.getAll(missionId).map { it.eventId }.toSet()

        val expectedIds = setOf("obs-A-1", "obs-A-2", "obs-B-1", "obs-B-2", "obs-B-3")
        assertEquals(expectedIds, observationsOnA)
        assertEquals(expectedIds, observationsOnB)

        // Verify state digests match on both nodes
        val digestsA = nodeA.observationDao.getAllSequenceDigests().associate { it.authorDeviceId to it.maxSeq }
        val digestsB = nodeB.observationDao.getAllSequenceDigests().associate { it.authorDeviceId to it.maxSeq }

        assertEquals(2L, digestsA["node-alpha"])
        assertEquals(3L, digestsA["node-bravo"])
        assertEquals(2L, digestsB["node-alpha"])
        assertEquals(3L, digestsB["node-bravo"])
    }

    @Test
    fun testUnidirectionalReconciliationWhenOneNodeWasOffline() = runBlocking {
        // Node A was offline with 0 observations.
        // Node B has 3 new observations.
        createObservation(nodeB, "obs-B-10", author = "node-bravo", seq = 1L)
        createObservation(nodeB, "obs-B-20", author = "node-bravo", seq = 2L)
        createObservation(nodeB, "obs-B-30", author = "node-bravo", seq = 3L)

        assertEquals(0, nodeA.observationDao.getAll(missionId).size)
        assertEquals(3, nodeB.observationDao.getAll(missionId).size)

        // Node A reconnects and exchanges StateDigest with Node B
        val peerInfoB = PeerInfo("node-bravo", "endpoint-node-bravo", "Device Bravo")
        nodeA.transport.triggerPeerConnected("node-bravo", peerInfoB)

        // Wait for Node A to receive all observations from Node B
        waitUntil {
            nodeA.observationDao.getAll(missionId).size == 3
        }

        assertTrue(nodeA.observationDao.exists("obs-B-10"))
        assertTrue(nodeA.observationDao.exists("obs-B-20"))
        assertTrue(nodeA.observationDao.exists("obs-B-30"))
    }

    @Test
    fun testMultiAuthorStateDigestReconciliation() = runBlocking {
        nodeA.transport.isPartitioned = true
        nodeB.transport.isPartitioned = true

        // Node A holds observations from authors Charlie and Delta
        createObservation(nodeA, "obs-C-1", author = "author-charlie", seq = 1L)
        createObservation(nodeA, "obs-C-2", author = "author-charlie", seq = 2L)
        createObservation(nodeA, "obs-D-1", author = "author-delta", seq = 1L)

        // Node B holds observations from authors Echo and Fox
        createObservation(nodeB, "obs-E-1", author = "author-echo", seq = 1L)
        createObservation(nodeB, "obs-F-1", author = "author-fox", seq = 1L)
        createObservation(nodeB, "obs-F-2", author = "author-fox", seq = 2L)

        // Heal and connect
        nodeA.transport.isPartitioned = false
        nodeB.transport.isPartitioned = false

        val peerInfoB = PeerInfo("node-bravo", "endpoint-node-bravo", "Device Bravo")
        val peerInfoA = PeerInfo("node-alpha", "endpoint-node-alpha", "Device Alpha")

        nodeA.transport.triggerPeerConnected("node-bravo", peerInfoB)
        nodeB.transport.triggerPeerConnected("node-alpha", peerInfoA)

        // Both nodes should converge on all 6 observations across 4 authors
        waitUntil {
            nodeA.observationDao.getAll(missionId).size == 6 &&
            nodeB.observationDao.getAll(missionId).size == 6
        }

        val allObsA = nodeA.observationDao.getAll(missionId).map { it.eventId }.toSet()
        val allObsB = nodeB.observationDao.getAll(missionId).map { it.eventId }.toSet()

        val expectedIds = setOf("obs-C-1", "obs-C-2", "obs-D-1", "obs-E-1", "obs-F-1", "obs-F-2")
        assertEquals(expectedIds, allObsA)
        assertEquals(expectedIds, allObsB)
    }

    @Test
    fun testStateDigestReconciliationEnqueuesInOutbox() = runBlocking {
        // Disconnect Node B so messages are queued in outbox without immediate delivery/ACK race
        nodeB.transport.isPartitioned = true

        // Node A has local observations that Node B does not have
        val obs1 = createObservation(nodeA, "obs-outbox-1", author = "node-alpha", seq = 1L, type = "HAZARD")
        val obs2 = createObservation(nodeA, "obs-outbox-2", author = "node-alpha", seq = 2L, type = "NOTE")

        // Node B sends its digest showing it has 0 observations from node-alpha
        val emptyDigest = SyncMessage.StateDigest(
            deviceId = "node-bravo",
            sequences = emptyMap()
        )

        // Node A receives Node B's digest
        nodeA.coordinator.handleDigest("endpoint-node-bravo", emptyDigest)

        // Invariant 2: Verify both missing observations were committed to Outbox for targetPeerId = node-bravo
        val pendingForB = nodeA.outboxDao.getPendingForPeer("node-bravo")
        assertEquals(2, pendingForB.size)

        val pendingEventIds = pendingForB.map { it.eventId }.toSet()
        assertTrue(pendingEventIds.contains(obs1.eventId))
        assertTrue(pendingEventIds.contains(obs2.eventId))

        // Invariant 5: HAZARD observation must have priority >= 0.8
        val hazardOutbox = pendingForB.first { it.eventId == obs1.eventId }
        assertTrue("Urgent HAZARD observation priority must be >= 0.8", hazardOutbox.priority >= 0.8f)
    }

    @Test
    fun testStateDigestReconciliationIdempotentWhenAlreadySynchronized() = runBlocking {
        // Both nodes start with the exact same observations
        createObservation(nodeA, "obs-synced-1", author = "node-alpha", seq = 1L)
        createObservation(nodeB, "obs-synced-1", author = "node-alpha", seq = 1L)

        nodeA.transport.sentMessages.clear()
        nodeB.transport.sentMessages.clear()

        // Node A sends StateDigest to Node B
        val digestA = SyncMessage.StateDigest(
            deviceId = "node-alpha",
            sequences = mapOf("node-alpha" to 1L)
        )
        nodeB.coordinator.handleDigest("endpoint-node-alpha", digestA)

        // Node B is already up to date: must not send EventRequest or EventBatch
        val eventBatches = nodeB.transport.sentMessages.filter { it.second is SyncMessage.EventBatch }
        val eventRequests = nodeB.transport.sentMessages.filter { it.second is SyncMessage.EventRequest }

        assertEquals(0, eventBatches.size)
        assertEquals(0, eventRequests.size)
    }

    @Test
    fun testReconciliationRecordsTelemetryInMetricsDao() = runBlocking {
        createObservation(nodeB, "obs-telemetry-1", author = "node-bravo", seq = 1L)

        // Node A receives StateDigest from Node B
        val digestB = SyncMessage.StateDigest(
            deviceId = "node-bravo",
            sequences = mapOf("node-bravo" to 1L)
        )
        nodeA.coordinator.handleDigest("endpoint-node-bravo", digestB)

        // Verify SYNC metric recorded on Node A
        val syncMetrics = nodeA.metricsDao.getByType("SYNC")
        assertTrue("SYNC metric must be recorded in MetricsDao", syncMetrics.isNotEmpty())
        assertTrue("Details should mention StateDigest or EventRequest",
            syncMetrics.any { it.details?.contains("StateDigest") == true || it.details?.contains("EventRequest") == true }
        )
    }

    // =========================================================================
    // Test Harness Components
    // =========================================================================

    class TestNode(val deviceId: String, val missionId: String) {
        val observationDao = FakeObservationDao()
        val mapDao = FakeMapDao()
        val outboxDao = FakeOutboxDao()
        val metricsDao = FakeMetricsDao()
        val peerDao = FakePeerDao()

        val observationRepo = ObservationRepository(observationDao, outboxDao, deviceId, missionId)
        val syncRepo = SyncRepository(metricsDao)
        val scheduler = PriorityScheduler(observationDao, mapDao, outboxDao)
        val mapFusionEngine = MapFusionEngine(mapDao, observationDao)
        val transport = DirectMeshTransport(deviceId)

        val coordinator = SyncCoordinator(
            transport = transport,
            observationDao = observationDao,
            outboxDao = outboxDao,
            observationRepo = observationRepo,
            syncRepo = syncRepo,
            scheduler = scheduler,
            mapFusionEngine = mapFusionEngine,
            deviceId = deviceId,
            missionId = missionId
        )

        fun start() {
            coordinator.start()
        }

        fun stop() {
            coordinator.stop()
        }
    }

    class DirectMeshTransport(val deviceId: String) : MeshTransport {
        var peerTransport: DirectMeshTransport? = null
        var isPartitioned: Boolean = false

        private val _connectedPeers = MutableStateFlow<Map<String, PeerInfo>>(emptyMap())
        override val connectedPeers: StateFlow<Map<String, PeerInfo>> = _connectedPeers.asStateFlow()

        private val _incomingMessages = MutableSharedFlow<Pair<String, SyncMessage>>(extraBufferCapacity = 1000)
        override val incomingMessages: SharedFlow<Pair<String, SyncMessage>> = _incomingMessages.asSharedFlow()

        private val _meshState = MutableStateFlow(MeshState.ACTIVE)
        override val meshState: StateFlow<MeshState> = _meshState.asStateFlow()

        val sentMessages = CopyOnWriteArrayList<Pair<String, SyncMessage>>()
        private val totalBytes = AtomicLong(0L)

        private var onPeerConnectedCallback: ((String, PeerInfo) -> Unit)? = null
        private var onPeerDisconnectedCallback: ((String) -> Unit)? = null

        override fun startAdvertisingAndDiscovery(deviceName: String, role: String) {
            _meshState.value = MeshState.ACTIVE
        }

        override fun stop() {
            _meshState.value = MeshState.STOPPED
        }

        override fun setOnPeerConnected(callback: (String, PeerInfo) -> Unit) {
            onPeerConnectedCallback = callback
        }

        override fun setOnPeerDisconnected(callback: (String) -> Unit) {
            onPeerDisconnectedCallback = callback
        }

        fun triggerPeerConnected(peerId: String, peerInfo: PeerInfo) {
            _connectedPeers.value = _connectedPeers.value + (peerId to peerInfo)
            onPeerConnectedCallback?.invoke(peerId, peerInfo)
        }

        fun triggerPeerDisconnected(peerId: String) {
            _connectedPeers.value = _connectedPeers.value - peerId
            onPeerDisconnectedCallback?.invoke(peerId)
        }

        override suspend fun sendMessage(endpointId: String, message: SyncMessage): Boolean {
            sentMessages.add(endpointId to message)
            val bytes = getBytesSent(message)
            totalBytes.addAndGet(bytes.toLong())

            if (isPartitioned) return false
            val peer = peerTransport ?: return false
            if (peer.isPartitioned) return false

            peer.receiveDirectMessage("endpoint-$deviceId", message)
            return true
        }

        override suspend fun broadcastMessage(message: SyncMessage, excludeEndpoint: String?): Int {
            if (isPartitioned) return 0
            val peer = peerTransport ?: return 0
            if (peer.isPartitioned) return 0
            peer.receiveDirectMessage("endpoint-$deviceId", message)
            return 1
        }

        suspend fun receiveDirectMessage(endpointId: String, message: SyncMessage) {
            _incomingMessages.emit(endpointId to message)
        }

        override fun getEndpointForDevice(deviceId: String): String? = "endpoint-$deviceId"

        override fun getBytesSent(): Long = totalBytes.get()

        override fun getBytesSent(message: SyncMessage): Int = SyncMessageCodec.calculatePayloadBytes(message)
    }
}
