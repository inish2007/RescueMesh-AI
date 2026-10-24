package com.rescuemesh.ai.mesh

import com.rescuemesh.ai.data.dao.ChatDao
import com.rescuemesh.ai.data.entity.ChatMessageEntity
import com.rescuemesh.ai.data.entity.DeliveryReceiptEntity
import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.data.entity.OutboxEntity
import com.rescuemesh.ai.data.repository.ObservationRepository
import com.rescuemesh.ai.data.repository.SyncRepository
import com.rescuemesh.ai.fakes.*
import com.rescuemesh.ai.intelligence.MapFusionEngine
import com.rescuemesh.ai.intelligence.PriorityScheduler
import com.rescuemesh.ai.model.PeerInfo
import com.rescuemesh.ai.model.SyncMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit Test Suite for Gate 3 Requirement R1 (Chat Mesh Sync, Dual-Resolution, and Astra G3-S13 Mitigation):
 * Verifies outbox dual-resolution, deduplication, multi-hop relay rules, remote role sanitization,
 * unique ACK tracking, and peer reconnect draining.
 */
class ChatMeshSyncTest {

    private lateinit var observationDao: FakeObservationDao
    private lateinit var mapDao: FakeMapDao
    private lateinit var outboxDao: FakeOutboxDao
    private lateinit var metricsDao: FakeMetricsDao
    private lateinit var peerDao: FakePeerDao
    private lateinit var chatDao: FakeChatMeshDao

    private lateinit var observationRepo: ObservationRepository
    private lateinit var syncRepo: SyncRepository
    private lateinit var scheduler: PriorityScheduler
    private lateinit var mapFusionEngine: MapFusionEngine
    private lateinit var transport: FakeChatMeshTransport
    private lateinit var syncCoordinator: SyncCoordinator

    private val localDeviceId = "node-alpha"
    private val missionId = "mission-chat-r1"

    @Before
    fun setUp() {
        observationDao = FakeObservationDao()
        mapDao = FakeMapDao()
        outboxDao = FakeOutboxDao()
        metricsDao = FakeMetricsDao()
        peerDao = FakePeerDao()
        chatDao = FakeChatMeshDao()

        observationRepo = ObservationRepository(observationDao, outboxDao, localDeviceId, missionId)
        syncRepo = SyncRepository(metricsDao)
        scheduler = PriorityScheduler(observationDao, mapDao, outboxDao, chatDao)
        mapFusionEngine = MapFusionEngine(mapDao, observationDao)
        transport = FakeChatMeshTransport()

        syncCoordinator = SyncCoordinator(
            transport = transport,
            observationDao = observationDao,
            outboxDao = outboxDao,
            observationRepo = observationRepo,
            syncRepo = syncRepo,
            scheduler = scheduler,
            mapFusionEngine = mapFusionEngine,
            deviceId = localDeviceId,
            missionId = missionId,
            chatDao = chatDao
        )
    }

    // =========================================================================
    // 1. Outbox Dual-Resolution
    // =========================================================================

    @Test
    fun testOutboxDualResolutionDrainsObservationsAndChatMessages() = runBlocking {
        transport.addPeer("peer-beta", "endpoint-beta")

        // 1. Insert Observation into ObservationDao
        val obs = ObservationEntity(
            eventId = "obs-101",
            missionId = missionId,
            authorDeviceId = localDeviceId,
            authorSequence = 1L,
            eventType = "HAZARD",
            title = "Gas Leak",
            observedAtDevice = 1000L
        )
        observationDao.insert(obs)
        outboxDao.insert(OutboxEntity(
            eventId = "obs-101",
            targetPeerId = "peer-beta",
            priority = 0.90f
        ))

        // 2. Insert ChatMessage into ChatDao
        val chat = ChatMessageEntity(
            messageId = "chat-202",
            missionId = missionId,
            senderId = localDeviceId,
            senderName = "Alpha",
            text = "Keep clear of the basement entrance.",
            timestamp = 1050L,
            status = ChatMessageEntity.STATUS_SENDING,
            isLocal = true,
            authorSequence = 1L
        )
        chatDao.insert(chat)
        outboxDao.insert(OutboxEntity(
            eventId = "chat-202",
            targetPeerId = "peer-beta",
            priority = 0.30f
        ))

        val count = syncCoordinator.processPendingOutbox(now = 2000L)
        assertEquals("Both items must be transmitted", 2, count)

        assertEquals(2, transport.sentMessages.size)

        // First message should be EventBatch with obs-101
        val firstMsg = transport.sentMessages[0].second
        assertTrue("First message must be EventBatch", firstMsg is SyncMessage.EventBatch)
        assertEquals("obs-101", (firstMsg as SyncMessage.EventBatch).events[0].eventId)

        // Second message should be ChatMessage with chat-202
        val secondMsg = transport.sentMessages[1].second
        assertTrue("Second message must be ChatMessage", secondMsg is SyncMessage.ChatMessage)
        assertEquals("chat-202", (secondMsg as SyncMessage.ChatMessage).messageId)
        assertEquals("Keep clear of the basement entrance.", (secondMsg as SyncMessage.ChatMessage).text)
    }

    @Test
    fun testOutboxEntryWithNeitherObservationNorChatMarksFailed() = runBlocking {
        transport.addPeer("peer-beta", "endpoint-beta")

        outboxDao.insert(OutboxEntity(
            eventId = "ghost-id-999",
            targetPeerId = "peer-beta",
            priority = 0.50f
        ))

        val count = syncCoordinator.processPendingOutbox(now = 2000L)
        assertEquals(0, count)
        assertEquals(0, transport.sentMessages.size)

        // The outbox entry must be marked FAILED
        val pending = outboxDao.getPendingByPriority()
        assertEquals("Failed entry must not remain in pending queue", 0, pending.size)
    }

    // =========================================================================
    // 2. Local Chat Message Creation & Draining
    // =========================================================================

    @Test
    fun testOnNewLocalChatMessageEnqueuesAndTransmits() = runBlocking {
        transport.addPeer("peer-beta", "endpoint-beta")

        val chat = ChatMessageEntity(
            messageId = "chat-local-1",
            missionId = missionId,
            senderId = localDeviceId,
            senderName = "Commander",
            text = "All teams rendezvous at Point Echo.",
            timestamp = 5000L,
            status = ChatMessageEntity.STATUS_QUEUED,
            isLocal = true
        )
        chatDao.insert(chat)

        syncCoordinator.onNewLocalChatMessage(chat)

        assertEquals("Must send message to connected peer", 1, transport.sentMessages.size)
        val sent = transport.sentMessages[0].second as SyncMessage.ChatMessage
        assertEquals("chat-local-1", sent.messageId)
        assertEquals("All teams rendezvous at Point Echo.", sent.text)

        val updated = chatDao.getMessage("chat-local-1")
        assertEquals(ChatMessageEntity.STATUS_SENDING, updated?.status)
    }

    @Test
    fun testOnNewLocalChatMessageQueuesWhenNoPeersConnected() = runBlocking {
        // No peers connected
        val chat = ChatMessageEntity(
            messageId = "chat-offline-1",
            missionId = missionId,
            senderId = localDeviceId,
            senderName = "Operator",
            text = "Holding position at north gate.",
            timestamp = 6000L,
            status = ChatMessageEntity.STATUS_QUEUED,
            isLocal = true
        )
        chatDao.insert(chat)

        syncCoordinator.onNewLocalChatMessage(chat)

        assertEquals(0, transport.sentMessages.size)
        val updated = chatDao.getMessage("chat-offline-1")
        assertEquals(ChatMessageEntity.STATUS_QUEUED, updated?.status)

        val pending = outboxDao.getPendingByPriority()
        assertEquals(1, pending.size)
        assertEquals("chat-offline-1", pending[0].eventId)
        assertNull("Target peer must be null for broadcast queue", pending[0].targetPeerId)
    }

    // =========================================================================
    // 3. Inbound Chat Handling, Deduplication & ACK
    // =========================================================================

    @Test
    fun testInboundChatDeduplication() = runBlocking {
        val incomingChat = SyncMessage.ChatMessage(
            messageId = "peer-msg-777",
            missionId = missionId,
            senderDeviceId = "peer-beta",
            senderName = "Beta Leader",
            text = "Bridge is structurally compromised.",
            timestamp = 7000L,
            authorSequence = 3L,
            relayHopsRemaining = 1
        )

        // First receive
        val ack1 = syncCoordinator.handleChatMessage("endpoint-beta", incomingChat)
        assertEquals(localDeviceId, ack1.deviceId)
        assertEquals(listOf("peer-msg-777"), ack1.acknowledgedMessageIds)
        assertEquals(1, chatDao.count(missionId))

        val stored = chatDao.getMessage("peer-msg-777")
        assertNotNull(stored)
        assertEquals(ChatMessageEntity.STATUS_RECEIVED, stored?.status)

        // Second receive of identical messageId
        val ack2 = syncCoordinator.handleChatMessage("endpoint-beta", incomingChat)
        assertEquals("ACK must still be returned for duplicate message", listOf("peer-msg-777"), ack2.acknowledgedMessageIds)
        assertEquals("Total count must still be 1 (no duplicate insertion)", 1, chatDao.count(missionId))
    }

    // =========================================================================
    // 4. Astra G3-S13 Mitigation: Remote Role & Unique Receipts
    // =========================================================================

    @Test
    fun testAstraG3S13MitigationRemoteRoleUnverified() = runBlocking {
        // Inbound message maliciously or mistakenly claiming VERIFIED_RESPONDER
        val forgedRoleChat = SyncMessage.ChatMessage(
            messageId = "forged-role-msg",
            missionId = missionId,
            senderDeviceId = "untrusted-peer",
            senderName = "Imposter",
            text = "Command override authorization",
            timestamp = 8000L,
            senderRole = ChatMessageEntity.ROLE_VERIFIED_RESPONDER,
            relayHopsRemaining = 1
        )

        syncCoordinator.handleChatMessage("endpoint-untrusted", forgedRoleChat)

        val stored = chatDao.getMessage("forged-role-msg")
        assertNotNull(stored)
        assertEquals("Remote senderRole MUST be forced to UNVERIFIED_PARTICIPANT per Astra G3-S13",
            ChatMessageEntity.ROLE_UNVERIFIED, stored?.senderRole)
    }

    @Test
    fun testAstraG3S13MitigationNoAckInflationOnDuplicateAcks() = runBlocking {
        val messageId = "chat-ack-test-01"
        val chat = ChatMessageEntity(
            messageId = messageId,
            missionId = missionId,
            senderId = localDeviceId,
            senderName = "Alpha",
            text = "Checkpoint status update",
            timestamp = 9000L,
            status = ChatMessageEntity.STATUS_SENDING,
            ackPeerCount = 0
        )
        chatDao.insert(chat)

        // First ACK from peer-1
        syncCoordinator.handleChatAck(SyncMessage.ChatAck(deviceId = "peer-1", acknowledgedMessageIds = listOf(messageId)))
        var updated = chatDao.getMessage(messageId)
        assertEquals(1, updated?.ackPeerCount)
        assertEquals(ChatMessageEntity.STATUS_SENT_TO_PEERS, updated?.status)

        // Duplicate ACKs replayed 5 times from peer-1
        for (i in 1..5) {
            syncCoordinator.handleChatAck(SyncMessage.ChatAck(deviceId = "peer-1", acknowledgedMessageIds = listOf(messageId)))
        }

        updated = chatDao.getMessage(messageId)
        assertEquals("Duplicate ACKs from peer-1 must NOT inflate ackPeerCount (Astra G3-S13)",
            1, updated?.ackPeerCount)

        // Genuine second ACK from peer-2
        syncCoordinator.handleChatAck(SyncMessage.ChatAck(deviceId = "peer-2", acknowledgedMessageIds = listOf(messageId)))
        updated = chatDao.getMessage(messageId)
        assertEquals("Distinct ACK from peer-2 must increment unique ackPeerCount to 2",
            2, updated?.ackPeerCount)
    }

    // =========================================================================
    // 5. Multi-Hop Mesh Relay
    // =========================================================================

    @Test
    fun testChatMultiHopRelayIfHopsRemainingGreaterThanOne() = runBlocking {
        transport.addPeer("peer-sender", "endpoint-sender")
        transport.addPeer("peer-receiver-1", "endpoint-rx1")
        transport.addPeer("peer-receiver-2", "endpoint-rx2")

        val incomingChat = SyncMessage.ChatMessage(
            messageId = "relay-chat-1",
            missionId = missionId,
            senderDeviceId = "peer-sender",
            senderName = "Sender",
            text = "Relay this across the perimeter.",
            timestamp = 10000L,
            authorSequence = 1L,
            relayHopsRemaining = 3
        )

        syncCoordinator.handleChatMessage("endpoint-sender", incomingChat)

        // Must NOT relay back to peer-sender
        val relaysToSender = transport.sentMessages.filter { it.first == "endpoint-sender" && it.second is SyncMessage.ChatMessage }
        assertEquals("Must never relay chat message back to the sender", 0, relaysToSender.size)

        // Must relay to other connected peers (endpoint-rx1, endpoint-rx2)
        val relays = transport.sentMessages.filter { it.second is SyncMessage.ChatMessage }
        assertEquals(2, relays.size)

        val relayedMsg = relays[0].second as SyncMessage.ChatMessage
        assertEquals("relay-chat-1", relayedMsg.messageId)
        assertEquals("Relay hop count must be decremented", 2, relayedMsg.relayHopsRemaining)
    }

    @Test
    fun testChatNoRelayIfHopsRemainingIsOne() = runBlocking {
        transport.addPeer("peer-sender", "endpoint-sender")
        transport.addPeer("peer-receiver-1", "endpoint-rx1")

        val incomingChat = SyncMessage.ChatMessage(
            messageId = "no-relay-chat",
            missionId = missionId,
            senderDeviceId = "peer-sender",
            senderName = "Sender",
            text = "Terminal hop message.",
            timestamp = 11000L,
            authorSequence = 1L,
            relayHopsRemaining = 1 // 1 hop remaining -> should NOT relay further
        )

        syncCoordinator.handleChatMessage("endpoint-sender", incomingChat)

        // Only ChatAck sent back to endpoint-sender, NO chat messages sent to rx1
        val chatMessagesSent = transport.sentMessages.filter { it.second is SyncMessage.ChatMessage }
        assertEquals("Terminal chat message (hops <= 1) must not be relayed", 0, chatMessagesSent.size)
    }

    // =========================================================================
    // 6. Cross-Mission Isolation & Reconnect Sync
    // =========================================================================

    @Test
    fun testCrossMissionChatDrop() = runBlocking {
        val foreignChat = SyncMessage.ChatMessage(
            messageId = "foreign-chat-1",
            missionId = "FOREIGN_MISSION_ZULU",
            senderDeviceId = "peer-foreign",
            senderName = "Zulu Ops",
            text = "Classified Zulu coordination.",
            timestamp = 12000L
        )

        val ack = syncCoordinator.handleChatMessage("endpoint-foreign", foreignChat)
        assertEquals("Foreign mission must return empty acknowledged list", emptyList<String>(), ack.acknowledgedMessageIds)
        assertEquals("Foreign chat message must NOT be stored in local ChatDao", 0, chatDao.count(missionId))
        assertNull(chatDao.getMessage("foreign-chat-1"))
    }

    @Test
    fun testPeerReconnectDrainsPendingChatOutbox() = runBlocking {
        syncCoordinator.start()
        try {
            // Enqueue chat message while offline
            val chat = ChatMessageEntity(
                messageId = "chat-reconnect-1",
                missionId = missionId,
                senderId = localDeviceId,
                senderName = "Alpha",
                text = "Waiting for peer reconnect.",
                timestamp = 13000L,
                status = ChatMessageEntity.STATUS_QUEUED,
                isLocal = true
            )
            chatDao.insert(chat)
            outboxDao.insert(OutboxEntity(
                eventId = "chat-reconnect-1",
                targetPeerId = null, // broadcast
                priority = 0.35f
            ))

            // Trigger peer connection callback
            transport.addPeer("peer-reconnected", "endpoint-reconnected")
            transport.peerConnectedCallback?.invoke("peer-reconnected", PeerInfo("peer-reconnected", "endpoint-reconnected", "peer-reconnected"))

            // Wait brief delay for coroutine launch in callback
            kotlinx.coroutines.delay(200)

            val chatTransmissions = transport.sentMessages.filter { it.second is SyncMessage.ChatMessage }
            assertEquals("Pending chat outbox message must be drained upon peer reconnection", 1, chatTransmissions.size)
            assertEquals("chat-reconnect-1", (chatTransmissions[0].second as SyncMessage.ChatMessage).messageId)
        } finally {
            syncCoordinator.stop()
        }
    }

    // =========================================================================
    // 7. Deterministic Store-and-Forward Relay (Phone A -> Phone B -> Phone C)
    // =========================================================================

    @Test
    fun testDeterministicStoreAndForwardRelayFromPhoneAToPhoneCThroughPhoneB() = runBlocking {
        // Phone B is this node (localDeviceId = "node-alpha")
        // Step 1: Phone A connects to Phone B
        transport.addPeer("phone-a", "endpoint-a")

        val phoneAMessage = SyncMessage.ChatMessage(
            messageId = "chat-saf-001",
            missionId = missionId,
            senderDeviceId = "phone-a",
            senderName = "Responder A",
            text = "Supplies needed at Sector 4.",
            timestamp = 20000L,
            authorSequence = 1L,
            relayHopsRemaining = 3
        )

        // Step 2: Phone B receives chat from Phone A
        val ack = syncCoordinator.handleChatMessage("endpoint-a", phoneAMessage)
        assertEquals(listOf("chat-saf-001"), ack.acknowledgedMessageIds)

        // Verify Phone B saved the message locally in Room
        val savedAtB = chatDao.getMessage("chat-saf-001")
        assertNotNull(savedAtB)
        assertEquals("chat-saf-001", savedAtB?.messageId)
        assertEquals(ChatMessageEntity.STATUS_RECEIVED, savedAtB?.status)
        assertEquals(ChatMessageEntity.ROLE_UNVERIFIED, savedAtB?.senderRole)

        // Step 3: Phone C was NOT connected yet -> Phone B stored it in Room
        // Now Phone C connects to Phone B (delayed physical transit / disconnected peer)
        transport.addPeer("phone-c", "endpoint-c")

        // Phone B queues message for Phone C via outbox
        outboxDao.insert(
            OutboxEntity(
                eventId = "chat-saf-001",
                targetPeerId = "phone-c",
                priority = scheduler.scoreChatMessage(savedAtB!!),
                maxHops = savedAtB.relayHopsRemaining - 1,
                status = "PENDING"
            )
        )

        // Phone B processes pending outbox
        val transmitted = syncCoordinator.processPendingOutbox(now = 25000L)
        assertEquals(1, transmitted)

        // Verify Phone C received the relayed chat message with decremented hops
        val sentToC = transport.sentMessages.filter { it.first == "endpoint-c" && it.second is SyncMessage.ChatMessage }
        assertEquals(1, sentToC.size)
        val relayedMsg = sentToC[0].second as SyncMessage.ChatMessage
        assertEquals("chat-saf-001", relayedMsg.messageId)
        assertEquals("phone-a", relayedMsg.senderDeviceId)
        assertEquals(2, relayedMsg.relayHopsRemaining) // 3 - 1 = 2 hops remaining
        assertEquals("Supplies needed at Sector 4.", relayedMsg.text)

        // Verify message was NEVER echoed back to Phone A
        val echoedToA = transport.sentMessages.filter { it.first == "endpoint-a" && it.second is SyncMessage.ChatMessage }
        assertEquals("Store-and-forward must never echo message back to originator", 0, echoedToA.size)
    }

    // =========================================================================
    // 8. Mission Isolation on Evidence Request and Digest Delta (Sentinel [P0-2])
    // =========================================================================

    @Test
    fun testMissionIsolationRejectsForeignMissionEvidenceRequestAndDigest() = runBlocking {
        transport.addPeer("peer-charlie", "endpoint-charlie")

        // 1. Observation belonging to foreign mission in local database
        val foreignObs = ObservationEntity(
            eventId = "obs-foreign-999",
            missionId = "FOREIGN_MISSION_OMEGA",
            authorDeviceId = localDeviceId,
            authorSequence = 10L,
            eventType = "HAZARD",
            title = "Foreign Hazard",
            evidencePath = "/tmp/foreign_photo.jpg",
            evidenceHash = "hash-omega-999",
            observedAtDevice = 1000L
        )
        observationDao.insert(foreignObs)

        // 2. Peer requests evidence for foreign mission event (spoofed with local missionId)
        val request = SyncMessage.EvidenceRequest(
            missionId = missionId, // Spoofed request claiming this mission
            eventId = "obs-foreign-999",
            evidenceHash = "hash-omega-999"
        )
        syncCoordinator.handleEvidenceRequest("endpoint-charlie", request)

        // Verify NO EvidenceHeader dispatched
        val headersDispatched = transport.sentMessages.filter { it.second is SyncMessage.EvidenceHeader }
        assertEquals("Must NOT serve evidence for foreign mission observation", 0, headersDispatched.size)

        // 3. Digest sync from peer: Ensure foreign mission observation is NOT enqueued or transmitted
        transport.sentMessages.clear()
        val emptyDigest = SyncMessage.StateDigest(
            deviceId = "peer-charlie",
            sequences = mapOf(localDeviceId to 0L)
        )
        syncCoordinator.handleDigest("endpoint-charlie", emptyDigest)

        // Outbox must be empty because foreign observation was filtered out by missionId
        val pendingOutbox = outboxDao.getPendingByPriority()
        assertEquals("Foreign mission observations must never be enqueued in outbox during digest delta", 0, pendingOutbox.size)
    }

    // =========================================================================
    // Test Doubles
    // =========================================================================

    private class FakeChatMeshDao : ChatDao {
        val messages = ConcurrentHashMap<String, ChatMessageEntity>()
        private var idSeq = 1L

        override suspend fun insert(message: ChatMessageEntity): Long {
            val id = if (message.id == 0L) idSeq++ else message.id
            messages[message.messageId] = message.copy(id = id)
            return id
        }

        override suspend fun insertIfAbsent(message: ChatMessageEntity): Long {
            if (messages.containsKey(message.messageId)) return -1L
            return insert(message)
        }

        override fun getMessagesFlow(missionId: String) =
            flowOf(messages.values.filter { it.missionId == missionId }.sortedBy { it.timestamp })

        override fun getLinkedMessagesFlow(missionId: String, eventId: String) =
            flowOf(messages.values.filter { it.missionId == missionId && it.linkedEventId == eventId }.sortedBy { it.timestamp })

        override suspend fun getMessage(messageId: String): ChatMessageEntity? = messages[messageId]

        override suspend fun getRecentMessages(missionId: String, limit: Int): List<ChatMessageEntity> =
            messages.values.filter { it.missionId == missionId }.sortedByDescending { it.timestamp }.take(limit)

        override suspend fun getAllMessages(missionId: String): List<ChatMessageEntity> =
            messages.values.filter { it.missionId == missionId }.sortedBy { it.timestamp }

        override suspend fun updateStatus(messageId: String, status: String) {
            messages[messageId]?.let { messages[messageId] = it.copy(status = status) }
        }

        override suspend fun updateAckCount(messageId: String, ackPeerCount: Int, status: String) {
            messages[messageId]?.let { messages[messageId] = it.copy(ackPeerCount = ackPeerCount, status = status) }
        }

        override suspend fun recordPeerAck(messageId: String) {
            messages[messageId]?.let { messages[messageId] = it.copy(ackPeerCount = it.ackPeerCount + 1, status = "SENT_TO_PEERS") }
        }

        override suspend fun exists(messageId: String): Int = if (messages.containsKey(messageId)) 1 else 0

        override suspend fun count(missionId: String): Int = messages.values.count { it.missionId == missionId }

        override suspend fun getLatestSequence(senderId: String): Long? =
            messages.values.filter { it.senderId == senderId }.maxOfOrNull { it.authorSequence }

        override suspend fun getMessagesAfterSequence(senderId: String, afterSequence: Long): List<ChatMessageEntity> =
            messages.values.filter { it.senderId == senderId && it.authorSequence > afterSequence }.sortedBy { it.authorSequence }

        override suspend fun pruneOldMessages(missionId: String, keepCount: Int): Int = 0
    }

    private class FakeChatMeshTransport : MeshTransport {
        private val _connectedPeers = MutableStateFlow<Map<String, PeerInfo>>(emptyMap())
        override val connectedPeers: StateFlow<Map<String, PeerInfo>> = _connectedPeers.asStateFlow()

        private val _incomingMessages = MutableSharedFlow<Pair<String, SyncMessage>>(extraBufferCapacity = 64)
        override val incomingMessages: SharedFlow<Pair<String, SyncMessage>> = _incomingMessages.asSharedFlow()

        private val _meshState = MutableStateFlow(MeshState.ACTIVE)
        override val meshState: StateFlow<MeshState> = _meshState.asStateFlow()

        val sentMessages = CopyOnWriteArrayList<Pair<String, SyncMessage>>()
        var peerConnectedCallback: ((String, PeerInfo) -> Unit)? = null
        var peerDisconnectedCallback: ((String) -> Unit)? = null

        fun addPeer(deviceId: String, endpointId: String, name: String = deviceId) {
            _connectedPeers.value = _connectedPeers.value + (deviceId to PeerInfo(deviceId, endpointId, name))
        }

        override fun startAdvertisingAndDiscovery(deviceName: String, role: String) {
            _meshState.value = MeshState.ACTIVE
        }

        override fun stop() {
            _meshState.value = MeshState.STOPPED
        }

        override fun setOnPeerConnected(callback: (String, PeerInfo) -> Unit) {
            peerConnectedCallback = callback
        }

        override fun setOnPeerDisconnected(callback: (String) -> Unit) {
            peerDisconnectedCallback = callback
        }

        override suspend fun sendMessage(endpointId: String, message: SyncMessage): Boolean {
            sentMessages.add(endpointId to message)
            return true
        }

        override suspend fun broadcastMessage(message: SyncMessage, excludeEndpoint: String?): Int {
            sentMessages.add("BROADCAST" to message)
            return _connectedPeers.value.size
        }

        override fun getEndpointForDevice(deviceId: String): String? =
            _connectedPeers.value[deviceId]?.endpointId ?: "endpoint-$deviceId"

        override fun getBytesSent(): Long = 0L

        override fun getBytesSent(message: SyncMessage): Int = 120
    }
}
