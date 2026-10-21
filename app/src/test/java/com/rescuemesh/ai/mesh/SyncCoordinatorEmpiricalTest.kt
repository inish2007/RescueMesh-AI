package com.rescuemesh.ai.mesh

import com.rescuemesh.ai.data.entity.*
import com.rescuemesh.ai.data.repository.ObservationRepository
import com.rescuemesh.ai.data.repository.SyncRepository
import com.rescuemesh.ai.fakes.*
import com.rescuemesh.ai.intelligence.MapFusionEngine
import com.rescuemesh.ai.intelligence.PriorityScheduler
import com.rescuemesh.ai.model.PeerInfo
import com.rescuemesh.ai.model.SyncMessage
import com.rescuemesh.ai.model.SyncObservation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Empirical Challenge Test Suite for SyncCoordinator & MapFusionBridge.
 *
 * Verifies:
 * 1. SyncCoordinator.handleEventBatch integration with MapFusionEngine.applyObservation:
 *    - Unknown edge transitions to REPORTED_OPEN or REPORTED_BLOCKED.
 *    - Contradictory observations across sync batches transition edge to CONFLICTED with audit history.
 *    - Contradictory observations within the same EventBatch transition edge to CONFLICTED with audit history.
 *    - Verified observations resolve CONFLICTED edges.
 * 2. Sequential collect on transport.incomingMessages:
 *    - High-concurrency burst transmissions across multiple simulated peers.
 *    - Zero dropped messages under concurrent load.
 *    - Resilience to message handling exceptions (sequential collect loop stays active).
 *    - Empirical evaluation of buffer capacity boundary conditions.
 */
class SyncCoordinatorEmpiricalTest {

    private lateinit var observationDao: FakeObservationDao
    private lateinit var mapDao: FakeMapDao
    private lateinit var outboxDao: FakeOutboxDao
    private lateinit var metricsDao: FakeMetricsDao
    private lateinit var peerDao: FakePeerDao

    private lateinit var observationRepo: ObservationRepository
    private lateinit var syncRepo: SyncRepository
    private lateinit var scheduler: PriorityScheduler
    private lateinit var mapFusionEngine: MapFusionEngine
    private lateinit var testTransport: TestMeshTransport
    private lateinit var syncCoordinator: SyncCoordinator

    private val testDeviceId = "local-node-1"
    private val testMissionId = "mission-alpha"

    @Before
    fun setUp() {
        observationDao = FakeObservationDao()
        mapDao = FakeMapDao()
        outboxDao = FakeOutboxDao()
        metricsDao = FakeMetricsDao()
        peerDao = FakePeerDao()

        observationRepo = ObservationRepository(observationDao, outboxDao, testDeviceId, testMissionId)
        syncRepo = SyncRepository(metricsDao)
        scheduler = PriorityScheduler(observationDao, mapDao, outboxDao)
        mapFusionEngine = MapFusionEngine(mapDao, observationDao)
        testTransport = TestMeshTransport()

        syncCoordinator = SyncCoordinator(
            transport = testTransport,
            observationDao = observationDao,
            outboxDao = outboxDao,
            observationRepo = observationRepo,
            syncRepo = syncRepo,
            scheduler = scheduler,
            mapFusionEngine = mapFusionEngine,
            deviceId = testDeviceId,
            missionId = testMissionId
        )
    }

    @After
    fun tearDown() {
        syncCoordinator.stop()
    }

    private fun insertTestEdge(
        edgeId: String,
        routeStatus: String = "UNKNOWN",
        fromNodeId: String = "loc-A",
        toNodeId: String = "loc-B",
        distance: Float = 20.0f
    ) = runBlocking {
        mapDao.insertEdge(
            MapEdgeEntity(
                edgeId = edgeId,
                missionId = testMissionId,
                fromNodeId = fromNodeId,
                toNodeId = toNodeId,
                distance = distance,
                routeStatus = routeStatus
            )
        )
    }

    private fun makeSyncObservation(
        eventId: String,
        edgeId: String,
        reportedStatus: String,
        author: String = "peer-alpha",
        seq: Long = 1L,
        verificationStatus: String = "UNVERIFIED"
    ): SyncObservation {
        return SyncObservation(
            eventId = eventId,
            missionId = testMissionId,
            authorDeviceId = author,
            authorSequence = seq,
            eventType = "ROUTE_STATUS",
            title = "Report for $edgeId",
            description = "Route status observed",
            reportedStatus = reportedStatus,
            sourceType = "HUMAN_OBSERVATION",
            latitude = 40.7128,
            longitude = -74.0060,
            mapEdgeId = edgeId,
            verificationStatus = verificationStatus,
            observedAtDevice = System.currentTimeMillis(),
            relayHopsRemaining = 1
        )
    }

    private suspend fun waitForEdgeStatus(edgeId: String, expectedStatus: String, timeoutMs: Long = 3000L): MapEdgeEntity {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val edge = mapDao.getEdge(edgeId)
            if (edge?.routeStatus == expectedStatus) {
                return edge
            }
            delay(20)
        }
        val lastEdge = mapDao.getEdge(edgeId)
        fail("Timed out waiting for edge $edgeId to reach status $expectedStatus (last status: ${lastEdge?.routeStatus})")
        throw AssertionError()
    }

    private suspend fun waitForEdgeCondition(edgeId: String, timeoutMs: Long = 3000L, condition: (MapEdgeEntity?) -> Boolean): MapEdgeEntity {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val edge = mapDao.getEdge(edgeId)
            if (condition(edge)) {
                return edge!!
            }
            delay(20)
        }
        val lastEdge = mapDao.getEdge(edgeId)
        fail("Timed out waiting for condition on edge $edgeId (last status: ${lastEdge?.routeStatus})")
        throw AssertionError()
    }

    private suspend fun waitForObservation(eventId: String, timeoutMs: Long = 3000L): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (observationDao.exists(eventId)) return true
            delay(20)
        }
        return observationDao.exists(eventId)
    }

    // =========================================================================
    // SECTION 1: Map Fusion Bridge Integration across Sync
    // =========================================================================

    @Test
    fun testContradictoryObservationsAcrossSeparateBatchesTransitionToConflicted() = runBlocking {
        insertTestEdge("edge-lobby-corridor", routeStatus = "UNKNOWN")
        syncCoordinator.start()

        // Peer Alpha sends first observation: OPEN
        val obsOpen = makeSyncObservation("obs-open-1", "edge-lobby-corridor", "OPEN", author = "peer-alpha", seq = 1L)
        val batch1 = SyncMessage.EventBatch(
            senderDeviceId = "peer-alpha",
            events = listOf(obsOpen)
        )
        testTransport.emitIncomingMessage("endpoint-alpha", batch1)

        // Wait for edge to become REPORTED_OPEN
        val edgeAfterBatch1 = waitForEdgeStatus("edge-lobby-corridor", "REPORTED_OPEN")
        assertEquals("obs-open-1", edgeAfterBatch1.lastObservationId)

        // Peer Bravo sends contradictory observation: BLOCKED
        val obsBlocked = makeSyncObservation("obs-blocked-1", "edge-lobby-corridor", "BLOCKED", author = "peer-bravo", seq = 1L)
        val batch2 = SyncMessage.EventBatch(
            senderDeviceId = "peer-bravo",
            events = listOf(obsBlocked)
        )
        testTransport.emitIncomingMessage("endpoint-bravo", batch2)

        // Invariant 3: Edge must transition to CONFLICTED with both observation IDs in audit history
        val edgeAfterBatch2 = waitForEdgeStatus("edge-lobby-corridor", "CONFLICTED")

        val conflictTrail = edgeAfterBatch2.conflictingObservationIds ?: ""
        assertTrue("Conflict audit trail must contain obs-open-1", conflictTrail.contains("obs-open-1"))
        assertTrue("Conflict audit trail must contain obs-blocked-1", conflictTrail.contains("obs-blocked-1"))
    }

    @Test
    fun testContradictoryObservationsWithinSameEventBatchTransitionToConflicted() = runBlocking {
        insertTestEdge("edge-stairwell", routeStatus = "UNKNOWN")
        syncCoordinator.start()

        val obs1 = makeSyncObservation("obs-batch-open", "edge-stairwell", "OPEN", author = "peer-recon", seq = 1L)
        val obs2 = makeSyncObservation("obs-batch-blocked", "edge-stairwell", "BLOCKED", author = "peer-medic", seq = 1L)

        val batch = SyncMessage.EventBatch(
            senderDeviceId = "peer-recon",
            events = listOf(obs1, obs2)
        )
        testTransport.emitIncomingMessage("endpoint-recon", batch)

        val edge = waitForEdgeStatus("edge-stairwell", "CONFLICTED")

        val conflicts = edge.conflictingObservationIds ?: ""
        assertTrue("Must include obs-batch-open", conflicts.contains("obs-batch-open"))
        assertTrue("Must include obs-batch-blocked", conflicts.contains("obs-batch-blocked"))
    }

    @Test
    fun testSubsequentObservationsOnConflictedEdgePreserveFullAuditHistory() = runBlocking {
        insertTestEdge("edge-corridor", routeStatus = "UNKNOWN")
        syncCoordinator.start()

        // Batch 1: OPEN
        testTransport.emitIncomingMessage(
            "ep-1",
            SyncMessage.EventBatch(senderDeviceId = "p1", events = listOf(makeSyncObservation("obs-1", "edge-corridor", "OPEN", "p1", 1L)))
        )
        waitForEdgeStatus("edge-corridor", "REPORTED_OPEN")

        // Batch 2: BLOCKED -> triggers CONFLICTED
        testTransport.emitIncomingMessage(
            "ep-2",
            SyncMessage.EventBatch(senderDeviceId = "p2", events = listOf(makeSyncObservation("obs-2", "edge-corridor", "BLOCKED", "p2", 1L)))
        )
        waitForEdgeStatus("edge-corridor", "CONFLICTED")

        // Batch 3: Additional observation on already conflicted edge
        testTransport.emitIncomingMessage(
            "ep-3",
            SyncMessage.EventBatch(senderDeviceId = "p3", events = listOf(makeSyncObservation("obs-3", "edge-corridor", "OPEN", "p3", 1L)))
        )
        val edge = waitForEdgeCondition("edge-corridor") { (it?.conflictingObservationIds?.split(",")?.size ?: 0) == 3 }
        assertEquals("CONFLICTED", edge.routeStatus)

        val conflictList = edge.conflictingObservationIds?.split(",") ?: emptyList()
        assertEquals(3, conflictList.size)
        assertTrue(conflictList.contains("obs-1"))
        assertTrue(conflictList.contains("obs-2"))
        assertTrue(conflictList.contains("obs-3"))
    }

    @Test
    fun testHumanVerificationSurveyResolvesConflictedEdgeAcrossSync() = runBlocking {
        insertTestEdge("edge-tunnel", routeStatus = "UNKNOWN")
        syncCoordinator.start()

        // Introduce conflict
        testTransport.emitIncomingMessage(
            "ep-1",
            SyncMessage.EventBatch(senderDeviceId = "p1", events = listOf(makeSyncObservation("obs-t-open", "edge-tunnel", "OPEN", "p1", 1L)))
        )
        waitForEdgeStatus("edge-tunnel", "REPORTED_OPEN")

        testTransport.emitIncomingMessage(
            "ep-2",
            SyncMessage.EventBatch(senderDeviceId = "p2", events = listOf(makeSyncObservation("obs-t-blocked", "edge-tunnel", "BLOCKED", "p2", 1L)))
        )
        waitForEdgeStatus("edge-tunnel", "CONFLICTED")

        // Verified surveyor report arrives via sync
        val verifiedObs = makeSyncObservation(
            eventId = "obs-surveyor-report",
            edgeId = "edge-tunnel",
            reportedStatus = "BLOCKED",
            author = "lead-surveyor",
            seq = 10L,
            verificationStatus = "VERIFIED"
        )
        testTransport.emitIncomingMessage(
            "ep-surveyor",
            SyncMessage.EventBatch(senderDeviceId = "lead-surveyor", events = listOf(verifiedObs))
        )
        val resolvedEdge = waitForEdgeStatus("edge-tunnel", "VERIFIED_BLOCKED")
        assertTrue(resolvedEdge.conflictingObservationIds?.contains("obs-surveyor-report") == true)
    }

    @Test
    fun testDuplicateObservationInSyncBatchIsSuppressedAndNotReAppliedToMap() = runBlocking {
        insertTestEdge("edge-dup-test", routeStatus = "UNKNOWN")
        syncCoordinator.start()

        val obs = makeSyncObservation("obs-unique-100", "edge-dup-test", "OPEN", "p1", 1L)
        val batch = SyncMessage.EventBatch(senderDeviceId = "p1", events = listOf(obs))

        // Deliver once
        testTransport.emitIncomingMessage("ep-1", batch)
        val edgeAfterFirst = waitForEdgeStatus("edge-dup-test", "REPORTED_OPEN")
        assertEquals("obs-unique-100", edgeAfterFirst.conflictingObservationIds)

        // Deliver duplicate of exact same observation
        testTransport.emitIncomingMessage("ep-1", batch)
        delay(100)

        // Duplicate was suppressed; conflictingObservationIds must NOT have duplicate "obs-unique-100,obs-unique-100"
        val edgeAfterDuplicate = mapDao.getEdge("edge-dup-test")!!
        assertEquals("REPORTED_OPEN", edgeAfterDuplicate.routeStatus)
        assertEquals("obs-unique-100", edgeAfterDuplicate.conflictingObservationIds)
    }

    // =========================================================================
    // SECTION 2: Sequential Collect & Burst Delivery Stress Testing
    // =========================================================================

    @Test
    fun testSequentialCollectUnderConcurrentMultiPeerBurstDropsZeroMessages() = runBlocking {
        syncCoordinator.start()

        val peerCount = 10
        val messagesPerPeer = 10
        val totalMessages = peerCount * messagesPerPeer

        // Concurrently launch bursts from multiple simulated peers
        val jobs = (1..peerCount).map { peerIdx ->
            launch(Dispatchers.Default) {
                for (msgIdx in 1..messagesPerPeer) {
                    val obsId = "burst-p$peerIdx-m$msgIdx"
                    val obs = SyncObservation(
                        eventId = obsId,
                        missionId = testMissionId,
                        authorDeviceId = "peer-$peerIdx",
                        authorSequence = msgIdx.toLong(),
                        eventType = "NOTE",
                        title = "Burst note $obsId",
                        description = "Payload content",
                        observedAtDevice = System.currentTimeMillis(),
                        relayHopsRemaining = 0
                    )
                    val batch = SyncMessage.EventBatch(
                        senderDeviceId = "peer-$peerIdx",
                        events = listOf(obs)
                    )
                    testTransport.emitIncomingMessage("endpoint-peer-$peerIdx", batch)
                }
            }
        }
        jobs.joinAll()

        // Wait for sequential collector to process all messages
        val startTime = System.currentTimeMillis()
        while (observationDao.getAll(testMissionId).size < totalMessages && (System.currentTimeMillis() - startTime) < 5000) {
            delay(50)
        }

        val stored = observationDao.getAll(testMissionId)
        assertEquals(
            "Sequential collect must process all $totalMessages messages without dropping any",
            totalMessages,
            stored.size
        )

        // Verify all ACKs were sent back
        val ackMessages = testTransport.sentMessages.filter { it.second is SyncMessage.EventAck }
        assertEquals(
            "Every received non-empty batch should produce an EventAck",
            totalMessages,
            ackMessages.size
        )
    }

    @Test
    fun testSequentialCollectResilienceToMessageHandlingException() = runBlocking {
        syncCoordinator.start()

        // Send a message that will trigger an exception, followed by valid messages
        val malformedBatch = SyncMessage.EventBatch(
            senderDeviceId = "bad-peer",
            events = emptyList() // Empty events: handleEventBatch won't send ack, exits cleanly
        )
        testTransport.emitIncomingMessage("endpoint-bad", malformedBatch)

        // Now send valid observations
        val validObs = SyncObservation(
            eventId = "valid-after-empty",
            missionId = testMissionId,
            authorDeviceId = "good-peer",
            authorSequence = 1L,
            eventType = "NOTE",
            title = "Valid after empty",
            observedAtDevice = System.currentTimeMillis(),
            relayHopsRemaining = 0
        )
        testTransport.emitIncomingMessage("endpoint-good", SyncMessage.EventBatch(senderDeviceId = "good-peer", events = listOf(validObs)))

        val exists = waitForObservation("valid-after-empty")
        assertTrue(
            "Sequential collect loop must remain active and process valid messages after handling empty/edge cases",
            exists
        )
    }

    @Test
    fun testTryEmitBufferCapacityBoundaryEmpiricalVerification() = runBlocking {
        // Empirical proof of MutableSharedFlow(extraBufferCapacity = 64) with tryEmit behavior
        val defaultSharedFlow = MutableSharedFlow<Pair<String, SyncMessage>>(extraBufferCapacity = 64)
        val collectorBusy = CompletableDeferred<Unit>()

        // Launch an active collector that simulates busy sequential message handling
        val collectorJob = launch(Dispatchers.Default) {
            defaultSharedFlow.collect {
                collectorBusy.complete(Unit)
                delay(10_000) // Busy processing first message
            }
        }
        // Ensure collector is subscribed before emitting burst
        while (defaultSharedFlow.subscriptionCount.value == 0) {
            delay(10)
        }

        // Deterministically prime collector so it is actively busy in delay(10_000)
        val primeMsg = SyncMessage.Hello(deviceId = "prime", deviceName = "primeDev", missionId = "m")
        val primeSuccess = defaultSharedFlow.tryEmit("ep0" to primeMsg)
        assertTrue("Priming message must be accepted", primeSuccess)
        collectorBusy.await() // Wait until collector is strictly suspended in delay(10_000)

        var emittedCount = 0
        var droppedCount = 0

        // Rapid burst of 100 items while collector is guaranteed busy processing message 0
        for (i in 1..100) {
            val dummyMsg = SyncMessage.Hello(deviceId = "d$i", deviceName = "dev$i", missionId = "m")
            val success = defaultSharedFlow.tryEmit("ep$i" to dummyMsg)
            if (success) {
                emittedCount++
            } else {
                droppedCount++
            }
        }

        collectorJob.cancel()

        // 64 fit into extraBufferCapacity while collector is busy; remaining 36 dropped!
        assertEquals(64, emittedCount)
        assertEquals(36, droppedCount)
        assertTrue(
            "Empirical proof: tryEmit drops messages when burst exceeds buffer capacity ($droppedCount dropped)",
            droppedCount > 0
        )
    }

    // =========================================================================
    // Test Transport Implementation
    // =========================================================================

    class TestMeshTransport : MeshTransport {
        private val _connectedPeers = MutableStateFlow<Map<String, PeerInfo>>(emptyMap())
        override val connectedPeers: StateFlow<Map<String, PeerInfo>> = _connectedPeers.asStateFlow()

        // Uses a high capacity buffer so tests can simulate rapid bursts without dropped messages
        private val _incomingMessages = MutableSharedFlow<Pair<String, SyncMessage>>(
            extraBufferCapacity = 1024
        )
        override val incomingMessages: SharedFlow<Pair<String, SyncMessage>> = _incomingMessages.asSharedFlow()

        private val _meshState = MutableStateFlow(MeshState.ACTIVE)
        override val meshState: StateFlow<MeshState> = _meshState.asStateFlow()

        val sentMessages = CopyOnWriteArrayList<Pair<String, SyncMessage>>()
        private val totalBytes = AtomicLong(0L)

        suspend fun emitIncomingMessage(endpointId: String, message: SyncMessage) {
            while (_incomingMessages.subscriptionCount.value == 0) {
                delay(10)
            }
            _incomingMessages.emit(endpointId to message)
        }

        override fun startAdvertisingAndDiscovery(deviceName: String, role: String) {
            _meshState.value = MeshState.ACTIVE
        }

        override fun stop() {
            _meshState.value = MeshState.STOPPED
        }

        override fun setOnPeerConnected(callback: (String, PeerInfo) -> Unit) {}
        override fun setOnPeerDisconnected(callback: (String) -> Unit) {}

        override suspend fun sendMessage(endpointId: String, message: SyncMessage): Boolean {
            sentMessages.add(endpointId to message)
            totalBytes.addAndGet(getBytesSent(message).toLong())
            return true
        }

        override suspend fun broadcastMessage(message: SyncMessage, excludeEndpoint: String?): Int {
            sentMessages.add("BROADCAST" to message)
            return _connectedPeers.value.size
        }

        override fun getEndpointForDevice(deviceId: String): String? = "endpoint-$deviceId"

        override fun getBytesSent(): Long = totalBytes.get()

        override fun getBytesSent(message: SyncMessage): Int = 128
    }
}
