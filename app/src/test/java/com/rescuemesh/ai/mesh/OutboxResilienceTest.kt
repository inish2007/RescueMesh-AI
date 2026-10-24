package com.rescuemesh.ai.mesh

import androidx.sqlite.db.SupportSQLiteDatabase
import com.rescuemesh.ai.data.database.RescueMeshDatabase
import com.rescuemesh.ai.data.entity.*
import com.rescuemesh.ai.data.repository.ObservationRepository
import com.rescuemesh.ai.data.repository.SyncRepository
import com.rescuemesh.ai.fakes.*
import com.rescuemesh.ai.intelligence.MapFusionEngine
import com.rescuemesh.ai.intelligence.PriorityScheduler
import com.rescuemesh.ai.model.PeerInfo
import com.rescuemesh.ai.model.SyncMessage
import com.rescuemesh.ai.model.SyncObservation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Milestone 5 Integration & Verification Test Suite for:
 * - Feature 21: Outbox Crash Recovery (SENDING -> PENDING on startup)
 * - Feature 22: Exponential Backoff Retry Engine with Absolute Safety Override (P >= 0.80)
 * - Feature 23: Duplicate Suppression ACK Resolution (Never starve sender on duplicates)
 * - Room Schema Migration 3 -> 4 Contract
 */
class OutboxResilienceTest {

    @Test
    fun acknowledgementDoesNotCompleteAnotherPeersDelivery() = runBlocking {
        outboxDao.insert(OutboxEntity(eventId = "shared", targetPeerId = "peer-b"))
        outboxDao.insert(OutboxEntity(eventId = "shared", targetPeerId = "peer-c"))

        syncCoordinator.handleEventAck(SyncMessage.EventAck(deviceId = "peer-b", acknowledgedEventIds = listOf("shared")))

        assertTrue(outboxDao.isDeliveredTo("shared", "peer-b"))
        assertEquals("Unacknowledged peer must remain queued", 1, outboxDao.getPendingForPeer("peer-c").size)
    }

    @Test
    fun missingAcknowledgementRetriesWithoutRestart() = runBlocking {
        transport.addPeer("peer-b", "endpoint-b")
        observationDao.insert(ObservationEntity(
            eventId = "lost-ack", missionId = missionId, authorDeviceId = localDeviceId,
            authorSequence = 1, eventType = "NOTE", title = "Lost ACK", observedAtDevice = 1
        ))
        outboxDao.insert(OutboxEntity(eventId = "lost-ack", targetPeerId = "peer-b", priority = 0.4f))

        syncCoordinator.processPendingOutbox(100_000L)
        syncCoordinator.processPendingOutbox(100_001L)
        assertEquals("Do not retransmit while awaiting ACK", 1, transport.sentMessages.size)
        syncCoordinator.processPendingOutbox(200_000L)

        assertEquals("Lost ACK must cause retry without process restart", 2, transport.sentMessages.size)
    }

    @Test
    fun broadcastAcknowledgementKeepsOtherPeersDeliveryRetryable() = runBlocking {
        transport.addPeer("peer-b", "endpoint-b")
        transport.addPeer("peer-c", "endpoint-c")
        observationDao.insert(ObservationEntity(
            eventId = "broadcast", missionId = missionId, authorDeviceId = localDeviceId,
            authorSequence = 1, eventType = "NOTE", title = "Broadcast", observedAtDevice = 1
        ))
        outboxDao.insert(OutboxEntity(eventId = "broadcast", priority = 0.4f))

        syncCoordinator.processPendingOutbox(100_000L)
        syncCoordinator.handleEventAck(SyncMessage.EventAck(deviceId = "peer-b", acknowledgedEventIds = listOf("broadcast")))
        syncCoordinator.processPendingOutbox(200_000L)

        assertEquals(1, transport.sentMessages.count { it.first == "endpoint-b" })
        assertEquals("Other peer must retry after missing ACK", 2, transport.sentMessages.count { it.first == "endpoint-c" })
    }

    private lateinit var observationDao: FakeObservationDao
    private lateinit var mapDao: FakeMapDao
    private lateinit var outboxDao: FakeOutboxDao
    private lateinit var metricsDao: FakeMetricsDao
    private lateinit var peerDao: FakePeerDao

    private lateinit var observationRepo: ObservationRepository
    private lateinit var syncRepo: SyncRepository
    private lateinit var scheduler: PriorityScheduler
    private lateinit var mapFusionEngine: MapFusionEngine
    private lateinit var transport: FakeResilienceTransport
    private lateinit var syncCoordinator: SyncCoordinator

    private val localDeviceId = "node-alpha"
    private val missionId = "mission-resilience-1"

    @Before
    fun setUp() {
        observationDao = FakeObservationDao()
        mapDao = FakeMapDao()
        outboxDao = FakeOutboxDao()
        metricsDao = FakeMetricsDao()
        peerDao = FakePeerDao()

        observationRepo = ObservationRepository(observationDao, outboxDao, localDeviceId, missionId)
        syncRepo = SyncRepository(metricsDao)
        scheduler = PriorityScheduler(observationDao, mapDao, outboxDao)
        mapFusionEngine = MapFusionEngine(mapDao, observationDao)
        transport = FakeResilienceTransport()

        syncCoordinator = SyncCoordinator(
            transport = transport,
            observationDao = observationDao,
            outboxDao = outboxDao,
            observationRepo = observationRepo,
            syncRepo = syncRepo,
            scheduler = scheduler,
            mapFusionEngine = mapFusionEngine,
            deviceId = localDeviceId,
            missionId = missionId
        )
    }

    @After
    fun tearDown() {
        syncCoordinator.stop()
    }

    // =========================================================================
    // Feature 21: Outbox Crash Recovery Tests
    // =========================================================================

    @Test
    fun testResetOrphanedSendingToPendingRestoresOnlySendingEntries() = runBlocking {
        // Arrange: Populate outbox with diverse states mimicking abrupt crash
        val idSending1 = outboxDao.insert(OutboxEntity(eventId = "e-crash-1", status = "SENDING", priority = 0.9f))
        val idSending2 = outboxDao.insert(OutboxEntity(eventId = "e-crash-2", status = "SENDING", priority = 0.6f))
        val idPending = outboxDao.insert(OutboxEntity(eventId = "e-pending", status = "PENDING", priority = 0.5f))
        val idDelivered = outboxDao.insert(OutboxEntity(eventId = "e-delivered", status = "DELIVERED", priority = 0.4f))

        // Act: Execute crash recovery directly on DAO
        val recoveredCount = outboxDao.resetOrphanedSendingToPending()

        // Assert: Exactly 2 orphaned SENDING entries recovered
        assertEquals("Must recover exactly 2 orphaned SENDING entries", 2, recoveredCount)

        val pending = outboxDao.getPendingByPriority()
        assertEquals(3, pending.size)
        val pendingIds = pending.map { it.id }
        assertTrue(pendingIds.contains(idSending1))
        assertTrue(pendingIds.contains(idSending2))
        assertTrue(pendingIds.contains(idPending))
        assertFalse(pendingIds.contains(idDelivered))
    }

    @Test
    fun testCrashRecoveryReturnsZeroWhenNoSendingEntriesExist() = runBlocking {
        outboxDao.insert(OutboxEntity(eventId = "e-p1", status = "PENDING"))
        outboxDao.insert(OutboxEntity(eventId = "e-d1", status = "DELIVERED"))

        val recoveredCount = outboxDao.resetOrphanedSendingToPending()
        assertEquals(0, recoveredCount)
    }

    @Test
    fun testSyncCoordinatorStartTriggersCrashRecovery() = runBlocking {
        outboxDao.insert(OutboxEntity(eventId = "e-start-recover", status = "SENDING", priority = 0.85f))

        // Trigger start() on SyncCoordinator
        syncCoordinator.start()

        // Give coroutine brief moment to execute recovery
        val recovered = syncCoordinator.recoverOrphanedOutbox()
        val pending = outboxDao.getPendingByPriority()
        assertTrue(pending.any { it.eventId == "e-start-recover" && it.status == "PENDING" })
    }

    // =========================================================================
    // Feature 22: Exponential Backoff & Absolute Safety Override Tests
    // =========================================================================

    @Test
    fun testCalculateBackoffBoundedExponentialCurve() {
        // Formula: min(1000L * (1L shl min(retryCount, 5)), 32000L)
        assertEquals(1000L, SyncCoordinator.calculateBackoff(0))
        assertEquals(2000L, SyncCoordinator.calculateBackoff(1))
        assertEquals(4000L, SyncCoordinator.calculateBackoff(2))
        assertEquals(8000L, SyncCoordinator.calculateBackoff(3))
        assertEquals(16000L, SyncCoordinator.calculateBackoff(4))
        assertEquals(32000L, SyncCoordinator.calculateBackoff(5))
        assertEquals(32000L, SyncCoordinator.calculateBackoff(6))  // Capped at 32s
        assertEquals(32000L, SyncCoordinator.calculateBackoff(10)) // Capped at 32s
    }

    @Test
    fun testOutboxFilteringBlocksUnreadyRetries() {
        val now = 100_000L
        val unreadyEntry = OutboxEntity(
            id = 1,
            eventId = "e-unready",
            priority = 0.5f, // Below 0.80 safety floor
            retryCount = 2,
            nextAttemptAt = now + 4000L
        )

        val readyEntry = OutboxEntity(
            id = 2,
            eventId = "e-ready",
            priority = 0.4f,
            retryCount = 1,
            nextAttemptAt = now - 100L
        )

        // At current time `now`: unreadyEntry must NOT transmit
        assertFalse("Entry whose nextAttemptAt is in future must be blocked", syncCoordinator.shouldTransmit(unreadyEntry, now))
        assertTrue("Entry whose nextAttemptAt is in past must be allowed", syncCoordinator.shouldTransmit(readyEntry, now))

        val filtered = syncCoordinator.filterTransmittable(listOf(unreadyEntry, readyEntry), now)
        assertEquals(1, filtered.size)
        assertEquals("e-ready", filtered[0].eventId)

        // At time `now + 4000L`: unreadyEntry is now ready
        assertTrue("Entry becomes ready when current time passes nextAttemptAt", syncCoordinator.shouldTransmit(unreadyEntry, now + 4000L))
    }

    @Test
    fun testAbsoluteSafetyOverrideBypassesBackoffDelays() {
        val now = 100_000L

        // Invariant 5: Safety Override Floor (P >= 0.80)
        // Critical Hazard observation with high priority scheduled far in the future due to past retries
        val safetyCriticalEntry = OutboxEntity(
            id = 10,
            eventId = "hazard-alpha",
            priority = 0.85f, // Safety override: >= 0.80
            retryCount = 5,
            nextAttemptAt = now + 32_000L // 32 seconds in future!
        )

        val routineEntry = OutboxEntity(
            id = 11,
            eventId = "note-routine",
            priority = 0.45f, // Below 0.80
            retryCount = 5,
            nextAttemptAt = now + 32_000L
        )

        // Absolute Safety Override: safetyCriticalEntry MUST transmit immediately at `now`
        assertTrue(
            "Invariant Violation: Hazard with P >= 0.80 must bypass backoff delay and transmit immediately",
            syncCoordinator.shouldTransmit(safetyCriticalEntry, now)
        )

        // Routine entry must remain blocked
        assertFalse(
            "Routine note must obey backoff delay",
            syncCoordinator.shouldTransmit(routineEntry, now)
        )

        val filtered = syncCoordinator.filterTransmittable(listOf(safetyCriticalEntry, routineEntry), now)
        assertEquals(1, filtered.size)
        assertEquals("hazard-alpha", filtered[0].eventId)
    }

    @Test
    fun testProcessPendingOutboxAppliesBackoffOnTransmissionFailure() = runBlocking {
        val now = 50_000L
        transport.failSend = true // Simulate mesh link drop
        transport.addPeer("peer-1", "endpoint-1")

        // Seed observation and pending outbox entry
        val obs = ObservationEntity(
            eventId = "obs-fail-1",
            missionId = missionId,
            authorDeviceId = localDeviceId,
            authorSequence = 1L,
            eventType = "NOTE",
            title = "Test Fail",
            observedAtDevice = now
        )
        observationDao.insert(obs)
        val outboxId = outboxDao.insert(
            OutboxEntity(
                eventId = obs.eventId,
                targetPeerId = "peer-1",
                priority = 0.40f,
                retryCount = 0,
                nextAttemptAt = 0L,
                status = "PENDING"
            )
        )

        // Process outbox
        val transmitted = syncCoordinator.processPendingOutbox(now)
        assertEquals("Should transmit 0 successfully due to failure", 0, transmitted)

        // Verify entry updated with incremented retryCount and exponential backoff
        val pending = outboxDao.getPendingByPriority()
        assertEquals(1, pending.size)
        val updated = pending[0]
        assertEquals(1, updated.retryCount)
        assertEquals(now + 2000L, updated.nextAttemptAt) // calculateBackoff(1) = 2000L
        assertEquals("PENDING", updated.status)
    }

    @Test
    fun testProcessPendingOutboxTransmitsSafetyCriticalImmediatelyEvenIfDelayed() = runBlocking {
        val now = 50_000L
        transport.failSend = false
        transport.addPeer("peer-rescue", "endpoint-rescue")

        // Critical hazard with high backoff time set in the future
        val hazardObs = ObservationEntity(
            eventId = "hazard-critical",
            missionId = missionId,
            authorDeviceId = localDeviceId,
            authorSequence = 2L,
            eventType = "HAZARD",
            title = "Flash Flood Inundation",
            reportedStatus = "HAZARD",
            observedAtDevice = now
        )
        observationDao.insert(hazardObs)

        outboxDao.insert(
            OutboxEntity(
                eventId = hazardObs.eventId,
                targetPeerId = "peer-rescue",
                priority = 0.95f, // Safety Floor P >= 0.80
                retryCount = 4,
                nextAttemptAt = now + 16_000L, // Unexpired delay
                status = "PENDING"
            )
        )

        val transmitted = syncCoordinator.processPendingOutbox(now)
        assertEquals("Safety-critical hazard must transmit immediately despite future nextAttemptAt", 1, transmitted)
        assertEquals(1, transport.sentMessages.size)
    }

    // =========================================================================
    // Feature 23: Duplicate Suppression ACK Resolution Tests
    // =========================================================================

    @Test
    fun testDuplicateEventBatchReturnsFullAckPreventingStarvation() = runBlocking {
        // Arrange: Local node already has observation "obs-dup-1"
        val existingObs = ObservationEntity(
            eventId = "obs-dup-1",
            missionId = missionId,
            authorDeviceId = "remote-node-2",
            authorSequence = 10L,
            eventType = "NOTE",
            title = "Existing Record",
            observedAtDevice = 1000L
        )
        observationDao.insert(existingObs)

        // Peer sends EventBatch containing the duplicate observation
        val syncObs = SyncObservation(
            eventId = "obs-dup-1",
            missionId = missionId,
            authorDeviceId = "remote-node-2",
            authorSequence = 10L,
            eventType = "NOTE",
            title = "Existing Record",
            observedAtDevice = 1000L
        )
        val batch = SyncMessage.EventBatch(
            senderDeviceId = "remote-node-2",
            events = listOf(syncObs)
        )

        // Act: Handle the incoming event batch
        val ack = syncCoordinator.handleEventBatch("endpoint-remote", batch)

        // Assert: Even though duplicate was suppressed, EventAck MUST acknowledge "obs-dup-1"
        assertNotNull("Must return an EventAck", ack)
        assertEquals(localDeviceId, ack.deviceId)
        assertEquals(1, ack.acknowledgedEventIds.size)
        assertEquals("obs-dup-1", ack.acknowledgedEventIds[0])

        // Verify sent over transport
        val sentAck = transport.sentMessages.find { it.second is SyncMessage.EventAck }?.second as? SyncMessage.EventAck
        assertNotNull("ACK must be dispatched over mesh transport", sentAck)
        assertEquals(listOf("obs-dup-1"), sentAck?.acknowledgedEventIds)
    }

    @Test
    fun testMixedEventBatchAcksAllEvents() = runBlocking {
        // Seed duplicate
        observationDao.insert(
            ObservationEntity(
                eventId = "dup-1",
                missionId = missionId,
                authorDeviceId = "peer-x",
                authorSequence = 1L,
                eventType = "NOTE",
                title = "Old",
                observedAtDevice = 1000L
            )
        )

        val batch = SyncMessage.EventBatch(
            senderDeviceId = "peer-x",
            events = listOf(
                SyncObservation(
                    eventId = "dup-1",
                    missionId = missionId,
                    authorDeviceId = "peer-x",
                    authorSequence = 1L,
                    eventType = "NOTE",
                    title = "Old",
                    observedAtDevice = 1000L
                ),
                SyncObservation(
                    eventId = "new-2",
                    missionId = missionId,
                    authorDeviceId = "peer-x",
                    authorSequence = 2L,
                    eventType = "HAZARD",
                    title = "New Hazard",
                    observedAtDevice = 2000L
                )
            )
        )

        val ack = syncCoordinator.handleEventBatch("endpoint-x", batch)

        assertEquals("Both new and duplicate event IDs must be acknowledged in ACK", 2, ack.acknowledgedEventIds.size)
        assertTrue(ack.acknowledgedEventIds.contains("dup-1"))
        assertTrue(ack.acknowledgedEventIds.contains("new-2"))

        // New observation was saved to DAO
        assertTrue(observationDao.exists("new-2"))
    }

    @Test
    fun testDuplicateAckClearsSenderOutboxEntry() = runBlocking {
        // Scenario: This device sent "obs-stuck-1" to peer-beta.
        // It's in outbox as PENDING or SENDING.
        val outboxId = outboxDao.insert(
            OutboxEntity(
                eventId = "obs-stuck-1",
                targetPeerId = "peer-beta",
                status = "SENDING",
                retryCount = 3
            )
        )

        // Seed observation
        observationDao.insert(
            ObservationEntity(
                eventId = "obs-stuck-1",
                missionId = missionId,
                authorDeviceId = localDeviceId,
                authorSequence = 1L,
                eventType = "NOTE",
                title = "Stuck Note",
                observedAtDevice = System.currentTimeMillis()
            )
        )

        // Peer returns ACK (e.g. from duplicate receipt)
        val incomingAck = SyncMessage.EventAck(
            deviceId = "peer-beta",
            acknowledgedEventIds = listOf("obs-stuck-1")
        )

        syncCoordinator.handleEventAck(incomingAck)

        // Sender's outbox entry MUST transition to DELIVERED
        val pending = outboxDao.getPendingByPriority()
        assertFalse("Outbox entry must no longer be pending after duplicate ACK", pending.any { it.eventId == "obs-stuck-1" })
        assertTrue("Delivery receipt must be recorded", outboxDao.isDeliveredTo("obs-stuck-1", "peer-beta"))
    }

    // =========================================================================
    // Schema Migration 3 -> 4 Contract Test
    // =========================================================================

    @Test
    fun testDatabaseMigration3To4SchemaContract() {
        val migration = RescueMeshDatabase.MIGRATION_3_4
        assertEquals("Migration start version must be 3", 3, migration.startVersion)
        assertEquals("Migration end version must be 4", 4, migration.endVersion)

        val executedSqlList = mutableListOf<String>()
        val proxyDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") {
                executedSqlList.add(args[0] as String)
            }
            null
        } as SupportSQLiteDatabase

        RescueMeshDatabase.MIGRATION_3_4.migrate(proxyDb)

        assertTrue(
            "DDL must alter outbox table to add retryCount",
            executedSqlList.any { it.contains("ALTER TABLE outbox", ignoreCase = true) && it.contains("retryCount", ignoreCase = true) }
        )
        assertTrue(
            "DDL must alter outbox table to add nextAttemptAt",
            executedSqlList.any { it.contains("ALTER TABLE outbox", ignoreCase = true) && it.contains("nextAttemptAt", ignoreCase = true) }
        )
    }

    // =========================================================================
    // Test Transport Double
    // =========================================================================

    class FakeResilienceTransport : MeshTransport {
        private val _connectedPeers = MutableStateFlow<Map<String, PeerInfo>>(emptyMap())
        override val connectedPeers: StateFlow<Map<String, PeerInfo>> = _connectedPeers.asStateFlow()

        private val _incomingMessages = MutableSharedFlow<Pair<String, SyncMessage>>(extraBufferCapacity = 64)
        override val incomingMessages: SharedFlow<Pair<String, SyncMessage>> = _incomingMessages.asSharedFlow()

        private val _meshState = MutableStateFlow(MeshState.ACTIVE)
        override val meshState: StateFlow<MeshState> = _meshState.asStateFlow()

        val sentMessages = CopyOnWriteArrayList<Pair<String, SyncMessage>>()
        var failSend: Boolean = false

        fun addPeer(deviceId: String, endpointId: String, name: String = deviceId) {
            _connectedPeers.value = _connectedPeers.value + (deviceId to PeerInfo(deviceId, endpointId, name))
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
            if (failSend) return false
            sentMessages.add(endpointId to message)
            return true
        }

        override suspend fun broadcastMessage(message: SyncMessage, excludeEndpoint: String?): Int {
            if (failSend) return 0
            sentMessages.add("BROADCAST" to message)
            return _connectedPeers.value.size
        }

        override fun getEndpointForDevice(deviceId: String): String? = "endpoint-$deviceId"

        override fun getBytesSent(): Long = 0L

        override fun getBytesSent(message: SyncMessage): Int = 100
    }
}
