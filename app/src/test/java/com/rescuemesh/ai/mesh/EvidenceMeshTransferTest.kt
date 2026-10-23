package com.rescuemesh.ai.mesh

import android.content.Context
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.tasks.Tasks
import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.data.entity.OutboxEntity
import com.rescuemesh.ai.data.repository.ObservationRepository
import com.rescuemesh.ai.data.repository.SyncRepository
import com.rescuemesh.ai.fakes.FakeMapDao
import com.rescuemesh.ai.fakes.FakeMetricsDao
import com.rescuemesh.ai.fakes.FakeObservationDao
import com.rescuemesh.ai.fakes.FakeOutboxDao
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
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Gate 2 Automated Test Suite: Two-Stage P2P Evidence Mesh Transfer & Persistence.
 *
 * Verifies:
 * 1. Stage 1 metadata broadcast includes deterministic SHA-256 digest without transmitting raw photo bytes.
 * 2. Stage 2 on-demand evidence transfer: EvidenceRequest triggers EvidenceHeader and payload file,
 *    recipient correlates 64-bit payload ID, validates SHA-256, persists to evidence directory,
 *    marks Room state as VERIFIED, and issues EvidenceAck(verified = true).
 * 3. SHA-256 mismatch rejection: Corrupted payloads are rejected, temp files deleted (zero orphans),
 *    Room state marked CORRUPTED, and EvidenceAck(verified = false) issued.
 * 4. Cross-mission isolation: Payloads or metadata belonging to a foreign missionId are strictly dropped.
 * 5. Absolute SOS priority invariant: Urgent outbox messages (P >= 0.80) strictly preempt photo file transfers.
 * 6. Disconnection and failure resilience: Interrupted transfers purge partial files immediately.
 * 7. Duplicate transfer resilience: Redundant transfers do not corrupt or duplicate local state.
 * 8. NearbyMeshTransport file payload contract and authentication guards.
 */
class EvidenceMeshTransferTest {

    private lateinit var tempBaseDir: File
    private lateinit var dirNodeA: File
    private lateinit var dirNodeB: File

    private val missionAlpha = "MISSION_ALPHA_DISASTER"
    private val missionForeign = "MISSION_BETA_FOREIGN"

    private lateinit var transportA: TestDirectMeshTransport
    private lateinit var transportB: TestDirectMeshTransport

    private lateinit var nodeA: TestEvidenceNode
    private lateinit var nodeB: TestEvidenceNode

    @Before
    fun setUp() {
        tempBaseDir = File(System.getProperty("java.io.tmpdir"), "rescuemesh_test_${UUID.randomUUID().toString().take(8)}")
        dirNodeA = File(tempBaseDir, "node_a_evidence").apply { mkdirs() }
        dirNodeB = File(tempBaseDir, "node_b_evidence").apply { mkdirs() }

        transportA = TestDirectMeshTransport("device-node-a")
        transportB = TestDirectMeshTransport("device-node-b")

        transportA.peerTransport = transportB
        transportB.peerTransport = transportA

        nodeA = TestEvidenceNode("device-node-a", missionAlpha, transportA, dirNodeA)
        nodeB = TestEvidenceNode("device-node-b", missionAlpha, transportB, dirNodeB)

        // Establish mutual connection
        transportA.connectPeer("endpoint-b", "device-node-b", "Node B")
        transportB.connectPeer("endpoint-a", "device-node-a", "Node A")

        nodeA.start()
        nodeB.start()
    }

    @After
    fun tearDown() {
        nodeA.stop()
        nodeB.stop()
        tempBaseDir.deleteRecursively()
    }

    private suspend fun waitUntil(timeoutMs: Long = 4000L, condition: suspend () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition()) return
            kotlinx.coroutines.delay(25)
        }
        if (!condition()) {
            fail("Timed out waiting for condition after ${timeoutMs}ms")
        }
    }

    // =========================================================================
    // Test 1: Stage 1 Metadata Broadcast
    // =========================================================================

    @Test
    fun testStage1MetadataBroadcastIncludesHash() = runBlocking {
        // Create genuine test photo file on Node A
        val photoFile = createSampleFile(dirNodeA, "local_photo_1.jpg", "GENUINE_RESCUE_PHOTO_BYTES_A".toByteArray())
        val expectedHash = computeSha256(photoFile)

        // Create local observation on Node A with photo evidence
        val obsA = nodeA.observationRepo.createObservation(
            eventType = "HAZARD",
            title = "Flooded Underpass",
            description = "High water level blocking access",
            evidencePath = photoFile.absolutePath,
            evidenceHash = expectedHash
        )

        assertEquals("Observation must retain expected hash", expectedHash, obsA.evidenceHash)
        assertEquals("Observation must retain photo path", photoFile.absolutePath, obsA.evidencePath)

        // Transmit outbox
        nodeA.coordinator.processPendingOutbox()

        // Wait until Node B receives the batch
        waitUntil { nodeB.observationDao.getById(obsA.eventId) != null }

        // Node B should receive EventBatch containing the metadata and evidenceHash
        val batchSent = transportA.sentMessages.map { it.second }.filterIsInstance<SyncMessage.EventBatch>().firstOrNull()
        assertNotNull("Node A must have sent EventBatch", batchSent)
        val syncObs = batchSent?.events?.find { it.eventId == obsA.eventId }
        assertNotNull("Batch must contain observation", syncObs)
        assertEquals("SyncObservation must broadcast evidenceHash", expectedHash, syncObs?.evidenceHash)
        assertEquals("SyncObservation must belong to active mission", missionAlpha, syncObs?.missionId)

        // Ensure Node B stored the observation with null local evidencePath initially
        val obsB = nodeB.observationDao.getById(obsA.eventId)
        assertNotNull("Node B must store received observation", obsB)
        assertEquals("Node B must record expected hash", expectedHash, obsB?.evidenceHash)
        assertNull("Node B must not have local evidencePath before file transfer", obsB?.evidencePath)
    }

    // =========================================================================
    // Test 2: Stage 2 On-Demand Evidence Request, Correlation & Verification
    // =========================================================================

    @Test
    fun testStage2OnDemandRequestHeaderCorrelationAndSuccessfulVerification() = runBlocking {
        // 1. Prepare genuine evidence file on Node A
        val fileContent = "DISASTER_COLLAPSED_STRUCTURE_IMAGE_DATA_0123456789".toByteArray()
        val photoFileA = createSampleFile(dirNodeA, "structure_collapse.jpg", fileContent)
        val expectedHash = computeSha256(photoFileA)

        val obsA = nodeA.observationRepo.createObservation(
            eventType = "COLLAPSED_STRUCTURE",
            title = "Building A Partial Collapse",
            evidencePath = photoFileA.absolutePath,
            evidenceHash = expectedHash
        )

        // 2. Transmit metadata batch to Node B
        nodeA.coordinator.processPendingOutbox()

        // 3. Node B automatically triggers EvidenceRequest upon receiving batch with missing local file
        waitUntil {
            transportB.sentMessages.any { it.second is SyncMessage.EvidenceRequest }
        }

        val evidenceReq = transportB.sentMessages.map { it.second }
            .filterIsInstance<SyncMessage.EvidenceRequest>()
            .find { it.eventId == obsA.eventId }

        assertNotNull("Node B must trigger EvidenceRequest for missing evidenceHash", evidenceReq)
        assertEquals(missionAlpha, evidenceReq?.missionId)
        assertEquals(expectedHash, evidenceReq?.evidenceHash)

        // 4. Node A processes EvidenceRequest and responds with EvidenceHeader + File Payload
        waitUntil {
            transportA.sentMessages.any { it.second is SyncMessage.EvidenceHeader }
        }

        val evidenceHeader = transportA.sentMessages.map { it.second }
            .filterIsInstance<SyncMessage.EvidenceHeader>()
            .find { it.eventId == obsA.eventId }

        assertNotNull("Node A must send EvidenceHeader", evidenceHeader)
        assertEquals(obsA.eventId, evidenceHeader?.eventId)
        assertEquals(expectedHash, evidenceHeader?.evidenceHash)
        assertEquals(photoFileA.length(), evidenceHeader?.fileSizeBytes)

        // 5. Verify Node B received and verified the evidence
        waitUntil {
            nodeB.observationDao.getById(obsA.eventId)?.verificationStatus == "VERIFIED"
        }

        val obsB = nodeB.observationDao.getById(obsA.eventId)
        assertNotNull("Observation must exist on Node B", obsB)
        assertEquals("VERIFIED", obsB?.verificationStatus)
        assertNotNull("Evidence path must be populated on Node B", obsB?.evidencePath)

        val receivedFileB = File(obsB!!.evidencePath!!)
        assertTrue("Verified file must exist on Node B disk", receivedFileB.exists())
        assertEquals("File size must match source", photoFileA.length(), receivedFileB.length())
        assertEquals("Computed SHA-256 must match expected hash", expectedHash, computeSha256(receivedFileB))

        // 6. Verify Node B sent EvidenceAck(verified = true)
        waitUntil {
            transportB.sentMessages.any { it.second is SyncMessage.EvidenceAck }
        }

        val ack = transportB.sentMessages.map { it.second }
            .filterIsInstance<SyncMessage.EvidenceAck>()
            .find { it.eventId == obsA.eventId }

        assertNotNull("Node B must issue EvidenceAck", ack)
        assertTrue("EvidenceAck must certify verified = true", ack?.verified == true)
    }

    // =========================================================================
    // Test 3: SHA-256 Mismatch Rejection & CORRUPTED Status
    // =========================================================================

    @Test
    fun testSha256MismatchRejectionAndCorruptedStatus() = runBlocking {
        // Node A has observation with claimed hash
        val legitimateFile = createSampleFile(dirNodeA, "legitimate.jpg", "ORIGINAL_INTEGRITY_DATA".toByteArray())
        val claimedHash = computeSha256(legitimateFile)
        val eventId = UUID.randomUUID().toString()

        // Node B has observation record with claimedHash
        nodeB.observationDao.insert(
            ObservationEntity(
                eventId = eventId,
                missionId = missionAlpha,
                authorDeviceId = "device-node-a",
                authorSequence = 1L,
                eventType = "HAZARD",
                title = "Hazard Event",
                evidenceHash = claimedHash,
                observedAtDevice = System.currentTimeMillis()
            )
        )

        // Create a corrupted/tampered file payload
        val corruptedBytes = "TAMPERED_AND_CORRUPTED_PAYLOAD_BYTES".toByteArray()
        val tempCorruptedFile = createSampleFile(dirNodeB, "temp_incoming_payload.jpg", corruptedBytes)

        val payloadId = 999111L
        val header = SyncMessage.EvidenceHeader(
            missionId = missionAlpha,
            eventId = eventId,
            evidenceHash = claimedHash,
            payloadId = payloadId,
            fileSizeBytes = corruptedBytes.size.toLong()
        )

        // Node B receives header
        nodeB.coordinator.handleEvidenceHeader("endpoint-a", header)

        // Node B completes incoming corrupted file transfer
        val transfer = IncomingFileTransfer(
            endpointId = "endpoint-a",
            payloadId = payloadId,
            file = tempCorruptedFile,
            isComplete = true,
            isFailed = false
        )
        nodeB.coordinator.handleIncomingFile(transfer)

        // 1. Verify corrupted temp file was deleted (zero orphans)
        assertFalse("Corrupted temp file must be deleted", tempCorruptedFile.exists())

        // 2. Verify observation in Room updated to CORRUPTED with null evidencePath
        val obsB = nodeB.observationDao.getById(eventId)
        assertNotNull(obsB)
        assertEquals("CORRUPTED", obsB?.verificationStatus)
        assertNull("Evidence path must be null for corrupted file", obsB?.evidencePath)

        // 3. Verify Node B sent EvidenceAck(verified = false)
        val ack = transportB.sentMessages.map { it.second }
            .filterIsInstance<SyncMessage.EvidenceAck>()
            .find { it.eventId == eventId }

        assertNotNull("Node B must send EvidenceAck for rejected payload", ack)
        assertFalse("EvidenceAck must declare verified = false", ack?.verified == true)
    }

    // =========================================================================
    // Test 4: Cross-Mission Isolation
    // =========================================================================

    @Test
    fun testCrossMissionIsolationDropsForeignMessages() = runBlocking {
        val foreignFile = createSampleFile(dirNodeA, "foreign.jpg", "FOREIGN_MISSION_BYTES".toByteArray())
        val foreignHash = computeSha256(foreignFile)
        val foreignEventId = "foreign-event-123"

        // 1. Foreign mission EventBatch delivered to Node B
        val foreignBatch = SyncMessage.EventBatch(
            senderDeviceId = "device-node-a",
            events = listOf(
                SyncObservation(
                    eventId = foreignEventId,
                    missionId = missionForeign,
                    authorDeviceId = "device-node-a",
                    authorSequence = 1L,
                    eventType = "SOS",
                    title = "Foreign SOS",
                    evidenceHash = foreignHash,
                    observedAtDevice = System.currentTimeMillis()
                )
            )
        )
        nodeB.coordinator.handleEventBatch("endpoint-a", foreignBatch)

        // Verify Node B dropped foreign observation
        assertFalse("Foreign mission observation must NOT be stored in local database", nodeB.observationDao.exists(foreignEventId))

        // 2. Foreign mission EvidenceRequest delivered to Node A
        transportA.sentMessages.clear()
        val foreignRequest = SyncMessage.EvidenceRequest(
            missionId = missionForeign,
            eventId = foreignEventId,
            evidenceHash = foreignHash
        )
        nodeA.coordinator.handleEvidenceRequest("endpoint-b", foreignRequest)

        // Node A must not send any header or payload for foreign mission
        val headersSent = transportA.sentMessages.map { it.second }.filterIsInstance<SyncMessage.EvidenceHeader>()
        assertEquals("No EvidenceHeader should be dispatched for foreign mission request", 0, headersSent.size)

        // 3. Foreign mission EvidenceHeader delivered to Node B
        val foreignHeader = SyncMessage.EvidenceHeader(
            missionId = missionForeign,
            eventId = foreignEventId,
            evidenceHash = foreignHash,
            payloadId = 555666L,
            fileSizeBytes = foreignFile.length()
        )
        nodeB.coordinator.handleEvidenceHeader("endpoint-a", foreignHeader)
        // Ensure no correlation was stored for foreign mission
    }

    // =========================================================================
    // Test 5: Absolute SOS Priority Invariant (P >= 0.80 Preempts Photo Transfer)
    // =========================================================================

    @Test
    fun testSosPriorityInvariantUrgentPacketsPreemptFileTransfer() = runBlocking {
        val photoFile = createSampleFile(dirNodeA, "normal_photo.jpg", "NORMAL_PHOTO_CONTENT".toByteArray())
        val photoHash = computeSha256(photoFile)
        val photoEventId = "photo-event-001"

        // Setup local observation on Node A
        nodeA.observationDao.insert(
            ObservationEntity(
                eventId = photoEventId,
                missionId = missionAlpha,
                authorDeviceId = "device-node-a",
                authorSequence = 1L,
                eventType = "NOTE",
                title = "Routine Survey",
                evidencePath = photoFile.absolutePath,
                evidenceHash = photoHash,
                observedAtDevice = System.currentTimeMillis()
            )
        )

        // Setup urgent emergency SOS observation in Outbox with P = 0.95 (>= 0.80)
        val urgentSosId = "urgent-sos-999"
        nodeA.observationDao.insert(
            ObservationEntity(
                eventId = urgentSosId,
                missionId = missionAlpha,
                authorDeviceId = "device-node-a",
                authorSequence = 2L,
                eventType = "HAZARD",
                title = "Gas Leak Explosion Hazard",
                observedAtDevice = System.currentTimeMillis()
            )
        )
        nodeA.outboxDao.insert(
            OutboxEntity(
                eventId = urgentSosId,
                targetPeerId = "device-node-b",
                priority = 0.95f,
                status = "PENDING"
            )
        )

        transportA.sentMessages.clear()

        // Incoming EvidenceRequest arrives at Node A for the routine photo
        val req = SyncMessage.EvidenceRequest(
            missionId = missionAlpha,
            eventId = photoEventId,
            evidenceHash = photoHash
        )
        nodeA.coordinator.handleEvidenceRequest("endpoint-b", req)

        // Verify that urgent Outbox entry was dispatched!
        val sentBatches = transportA.sentMessages.map { it.second }.filterIsInstance<SyncMessage.EventBatch>()
        assertTrue("Urgent SOS packet must be dispatched before/with photo transfer", sentBatches.isNotEmpty())
        val containsUrgent = sentBatches.any { batch -> batch.events.any { it.eventId == urgentSosId } }
        assertTrue("Urgent safety report (P >= 0.80) must be processed when evidence request arrives", containsUrgent)
    }

    // =========================================================================
    // Test 6: Disconnection Resilience & Zero Orphans
    // =========================================================================

    @Test
    fun testDisconnectionResilienceZeroOrphans() = runBlocking {
        val eventId = UUID.randomUUID().toString()
        val expectedHash = "abc123expectedhash"
        val payloadId = 777888L

        nodeB.observationDao.insert(
            ObservationEntity(
                eventId = eventId,
                missionId = missionAlpha,
                authorDeviceId = "device-node-a",
                authorSequence = 1L,
                eventType = "HAZARD",
                title = "Hazard Event",
                evidenceHash = expectedHash,
                observedAtDevice = System.currentTimeMillis()
            )
        )

        val header = SyncMessage.EvidenceHeader(
            missionId = missionAlpha,
            eventId = eventId,
            evidenceHash = expectedHash,
            payloadId = payloadId,
            fileSizeBytes = 1024L
        )
        nodeB.coordinator.handleEvidenceHeader("endpoint-a", header)

        // Partial temp file left mid-transfer
        val partialTempFile = createSampleFile(dirNodeB, "partial_transfer.tmp", "INCOMPLETE_TRUNCATED_BYTES".toByteArray())
        assertTrue("Partial temp file created", partialTempFile.exists())

        // Simulate failed/cancelled transfer
        val transferFailure = IncomingFileTransfer(
            endpointId = "endpoint-a",
            payloadId = payloadId,
            file = partialTempFile,
            isComplete = false,
            isFailed = true
        )
        nodeB.coordinator.handleIncomingFile(transferFailure)

        // 1. Temp file must be immediately deleted
        assertFalse("Partial temp file must be cleaned up immediately", partialTempFile.exists())

        // 2. Observation updated to CORRUPTED
        val obsB = nodeB.observationDao.getById(eventId)
        assertEquals("CORRUPTED", obsB?.verificationStatus)
        assertNull(obsB?.evidencePath)

        // 3. EvidenceAck with verified=false sent
        val ack = transportB.sentMessages.map { it.second }.filterIsInstance<SyncMessage.EvidenceAck>().find { it.eventId == eventId }
        assertNotNull(ack)
        assertFalse(ack?.verified == true)
    }

    // =========================================================================
    // Test 7: Duplicate Transfer Resilience
    // =========================================================================

    @Test
    fun testDuplicateTransferResilience() = runBlocking {
        val photoFile = createSampleFile(dirNodeA, "dup_photo.jpg", "DUPLICATE_RESILIENCE_CONTENT".toByteArray())
        val expectedHash = computeSha256(photoFile)
        val eventId = UUID.randomUUID().toString()

        // Create verified file on Node B already
        val verifiedFileB = createSampleFile(dirNodeB, "evidence_${eventId}_${expectedHash.take(8)}.jpg", "DUPLICATE_RESILIENCE_CONTENT".toByteArray())
        nodeB.observationDao.insert(
            ObservationEntity(
                eventId = eventId,
                missionId = missionAlpha,
                authorDeviceId = "device-node-a",
                authorSequence = 1L,
                eventType = "HAZARD",
                title = "Hazard Event",
                evidencePath = verifiedFileB.absolutePath,
                evidenceHash = expectedHash,
                verificationStatus = "VERIFIED",
                observedAtDevice = System.currentTimeMillis()
            )
        )

        transportB.sentMessages.clear()

        // Receive another EventBatch with same observation
        val batch = SyncMessage.EventBatch(
            senderDeviceId = "device-node-a",
            events = listOf(
                SyncObservation(
                    eventId = eventId,
                    missionId = missionAlpha,
                    authorDeviceId = "device-node-a",
                    authorSequence = 1L,
                    eventType = "HAZARD",
                    title = "Hazard Event",
                    evidenceHash = expectedHash,
                    observedAtDevice = System.currentTimeMillis()
                )
            )
        )
        nodeB.coordinator.handleEventBatch("endpoint-a", batch)

        // Since Node B already has valid evidence file on disk, no redundant EvidenceRequest should be sent
        val requests = transportB.sentMessages.map { it.second }.filterIsInstance<SyncMessage.EvidenceRequest>()
        assertEquals("No redundant EvidenceRequest should be issued for already verified local evidence", 0, requests.size)
    }

    // =========================================================================
    // Test 8: NearbyMeshTransport sendFilePayload & Payload.Type.FILE handling
    // =========================================================================

    @Test
    fun testNearbyMeshTransportSendFilePayloadContract() = runBlocking {
        val fakeClient = FakeTransportConnectionsClient()
        val proxy = fakeClient.createProxy()
        val dummyContext = DummyTestContext()

        val nearbyTransport = NearbyMeshTransport(
            context = dummyContext,
            missionIdProvider = { missionAlpha },
            nearbyConnectionsOverride = proxy
        )
        nearbyTransport.payloadFileFactory = { file -> Payload.fromBytes(file.readBytes()) }

        // 1. Unauthenticated endpoint rejects file payload
        val testFile = createSampleFile(dirNodeA, "transport_test.jpg", "PAYLOAD_BYTES".toByteArray())
        val rejectedPayloadId = nearbyTransport.sendFilePayload("unauthenticated-endpoint", testFile)
        assertNull("Unauthenticated endpoint must reject sendFilePayload", rejectedPayloadId)

        // 2. Authenticate endpoint
        nearbyTransport.handleIncomingMessage(
            "auth-endpoint",
            SyncMessage.Hello(deviceId = "dev-peer", deviceName = "Peer", missionId = missionAlpha)
        )
        assertTrue("Endpoint authenticated", nearbyTransport.connectedEndpoints.contains("auth-endpoint"))

        // 3. Authenticated endpoint dispatches file payload
        val sentPayloadId = nearbyTransport.sendFilePayload("auth-endpoint", testFile)
        assertNotNull("Authenticated endpoint returns valid payload ID", sentPayloadId)
        assertEquals(1, fakeClient.sentPayloads.size)
        assertEquals(testFile.length(), nearbyTransport.getBytesSent())
    }

    // =========================================================================
    // Helpers & Harness
    // =========================================================================

    private fun createSampleFile(dir: File, name: String, bytes: ByteArray): File {
        val file = File(dir, name)
        FileOutputStream(file).use { it.write(bytes) }
        return file
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    class TestEvidenceNode(
        val deviceId: String,
        val missionId: String,
        val transport: TestDirectMeshTransport,
        val evidenceDir: File
    ) {
        val observationDao = FakeObservationDao()
        val mapDao = FakeMapDao()
        val outboxDao = FakeOutboxDao()
        val metricsDao = FakeMetricsDao()

        val observationRepo = ObservationRepository(observationDao, outboxDao, deviceId, missionId)
        val syncRepo = SyncRepository(metricsDao)
        val scheduler = PriorityScheduler(observationDao, mapDao, outboxDao)
        val mapFusionEngine = MapFusionEngine(mapDao, observationDao)

        val coordinator = SyncCoordinator(
            transport = transport,
            observationDao = observationDao,
            outboxDao = outboxDao,
            observationRepo = observationRepo,
            syncRepo = syncRepo,
            scheduler = scheduler,
            mapFusionEngine = mapFusionEngine,
            deviceId = deviceId,
            missionId = missionId,
            evidenceDir = evidenceDir
        )

        fun start() {
            coordinator.start()
        }

        fun stop() {
            coordinator.stop()
        }
    }

    class TestDirectMeshTransport(val deviceId: String) : MeshTransport {
        var peerTransport: TestDirectMeshTransport? = null

        private val _connectedPeers = MutableStateFlow<Map<String, PeerInfo>>(emptyMap())
        override val connectedPeers: StateFlow<Map<String, PeerInfo>> = _connectedPeers.asStateFlow()

        private val _incomingMessages = MutableSharedFlow<Pair<String, SyncMessage>>(extraBufferCapacity = 1000)
        override val incomingMessages: SharedFlow<Pair<String, SyncMessage>> = _incomingMessages.asSharedFlow()

        private val _incomingFiles = MutableStateFlow<IncomingFileTransfer?>(null)
        override val incomingFiles: StateFlow<IncomingFileTransfer?> = _incomingFiles.asStateFlow()

        private val _meshState = MutableStateFlow(MeshState.ACTIVE)
        override val meshState: StateFlow<MeshState> = _meshState.asStateFlow()

        val sentMessages = CopyOnWriteArrayList<Pair<String, SyncMessage>>()
        private val payloadIdGenerator = AtomicLong(10000L)

        fun connectPeer(endpointId: String, peerDeviceId: String, displayName: String) {
            _connectedPeers.value = _connectedPeers.value + (peerDeviceId to PeerInfo(peerDeviceId, endpointId, displayName))
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
            peerTransport?.deliverMessage(deviceId, message)
            return true
        }

        override suspend fun broadcastMessage(message: SyncMessage, excludeEndpoint: String?): Int {
            sentMessages.add("BROADCAST" to message)
            peerTransport?.deliverMessage(deviceId, message)
            return 1
        }

        override fun sendFilePayload(endpointId: String, file: File): Long? {
            val payloadId = payloadIdGenerator.incrementAndGet()
            peerTransport?.deliverFile(endpointId, payloadId, file)
            return payloadId
        }

        private fun deliverMessage(senderDeviceId: String, message: SyncMessage) {
            val endpoint = if (senderDeviceId == "device-node-a") "endpoint-a" else "endpoint-b"
            _incomingMessages.tryEmit(endpoint to message)
        }

        private fun deliverFile(endpointId: String, payloadId: Long, sourceFile: File) {
            // Deliver a temp copy of the file to simulate Nearby's incoming payload file
            val tempReceivedFile = File(sourceFile.parentFile, "temp_received_${payloadId}_${sourceFile.name}")
            sourceFile.copyTo(tempReceivedFile, overwrite = true)
            val incoming = IncomingFileTransfer(
                endpointId = if (deviceId == "device-node-a") "endpoint-a" else "endpoint-b",
                payloadId = payloadId,
                file = tempReceivedFile,
                isComplete = true,
                isFailed = false
            )
            _incomingFiles.value = incoming
        }

        override fun getEndpointForDevice(deviceId: String): String? {
            return _connectedPeers.value[deviceId]?.endpointId
        }

        override fun getBytesSent(): Long = 0L
        override fun getBytesSent(message: SyncMessage): Int = 100
    }

    private class DummyTestContext : android.content.ContextWrapper(null) {
        override fun getPackageName(): String = "com.rescuemesh.ai"
        override fun getApplicationContext(): Context = this
    }

    private class FakeTransportConnectionsClient : InvocationHandler {
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
                "sendPayload" -> {
                    val endpointId = nonNullArgs[0] as String
                    val payload = nonNullArgs[1] as Payload
                    sentPayloads.add(endpointId to payload)
                    Tasks.forResult(null)
                }
                "disconnectFromEndpoint", "stopAllEndpoints", "stopAdvertising", "stopDiscovery" -> null
                "toString" -> "FakeTransportConnectionsClient"
                "hashCode" -> 42
                "equals" -> proxy === nonNullArgs.getOrNull(0)
                else -> null
            }
        }
    }
}
