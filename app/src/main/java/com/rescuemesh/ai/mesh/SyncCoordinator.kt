package com.rescuemesh.ai.mesh

import android.util.Log
import com.rescuemesh.ai.data.dao.ChatDao
import com.rescuemesh.ai.data.dao.ObservationDao
import com.rescuemesh.ai.data.dao.OutboxDao
import com.rescuemesh.ai.data.entity.ChatMessageEntity
import com.rescuemesh.ai.data.entity.DeliveryReceiptEntity
import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.data.entity.OutboxEntity
import com.rescuemesh.ai.data.repository.ObservationRepository
import com.rescuemesh.ai.data.repository.SyncRepository
import com.rescuemesh.ai.intelligence.MapFusionEngine
import com.rescuemesh.ai.intelligence.PriorityScheduler
import com.rescuemesh.ai.model.PeerInfo
import com.rescuemesh.ai.model.SyncMessage
import com.rescuemesh.ai.model.SyncObservation
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates synchronization between this device and connected peers.
 *
 * Protocol:
 * 1. On peer connect → send STATE_DIGEST (author→latestSequence map)
 * 2. Peer compares digest → sends EVENT_REQUEST for missing events
 * 3. This device sends EVENT_BATCH with requested events (priority-ordered)
 * 4. Peer sends EVENT_ACK for received events
 * 5. On new local observation or chat message → push to all connected peers
 * 6. Periodic re-sync every 30 seconds for missed events
 */
class SyncCoordinator(
    private val transport: MeshTransport,
    private val observationDao: ObservationDao,
    private val outboxDao: OutboxDao,
    private val observationRepo: ObservationRepository,
    private val syncRepo: SyncRepository,
    private val scheduler: PriorityScheduler,
    private val mapFusionEngine: MapFusionEngine? = null,
    private val deviceId: String,
    private val missionId: String,
    evidenceDir: File? = null,
    evidenceDirectory: File? = null,
    chatDao: ChatDao? = null
) {
    var chatDao: ChatDao? = chatDao
    companion object {
        private const val TAG = "SyncCoordinator"
        private const val SYNC_INTERVAL_MS = 30_000L  // Re-sync every 30 seconds
        const val SAFETY_PRIORITY_FLOOR = 0.80f
        const val BASE_BACKOFF_MS = 1000L
        const val MAX_BACKOFF_MS = 32000L
        const val ACK_TIMEOUT_MS = 30_000L

        /**
         * Calculates bounded exponential backoff delay:
         * min(1000L * (1L shl min(retryCount, 5)), 32000L).
         */
        fun calculateBackoff(retryCount: Int): Long {
            val shift = minOf(maxOf(0, retryCount), 5)
            return minOf(BASE_BACKOFF_MS * (1L shl shift), MAX_BACKOFF_MS)
        }
    }

    private val evidenceDirectory: File = evidenceDir ?: evidenceDirectory ?: run {
        val base = try {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.app.Application
            app?.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: ".", "evidence")
        } catch (_: Throwable) {
            File(System.getProperty("java.io.tmpdir") ?: ".", "evidence")
        }
        File(base, "evidence").apply { if (!exists()) mkdirs() }
    }

    // Correlation mappings: payloadId -> EvidenceHeader
    private val headersByPayloadId = ConcurrentHashMap<Long, SyncMessage.EvidenceHeader>()
    // Out-of-order completed files: payloadId -> IncomingFileTransfer
    private val pendingFilesByPayloadId = ConcurrentHashMap<Long, IncomingFileTransfer>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null
    private val outboxMutex = Mutex()

    /**
     * Crash recovery: Reset any orphaned SENDING entries in the outbox back to PENDING.
     */
    suspend fun recoverOrphanedOutbox(): Int {
        return outboxDao.resetOrphanedSendingToPending()
    }

    /**
     * Determines if an outbox entry should be transmitted based on retry backoff
     * and the Absolute Safety Override (P >= 0.80).
     */
    fun shouldTransmit(entry: OutboxEntity, now: Long = System.currentTimeMillis()): Boolean {
        // Absolute Safety Override: Observations with P >= 0.80 bypass backoff delays and transmit immediately
        if (entry.priority >= SAFETY_PRIORITY_FLOOR) {
            return true
        }
        return now >= entry.nextAttemptAt
    }

    /**
     * Filters outbox entries eligible for transmission at the specified time.
     */
    fun filterTransmittable(entries: List<OutboxEntity>, now: Long = System.currentTimeMillis()): List<OutboxEntity> {
        return entries.filter { shouldTransmit(it, now) }
    }

    fun start() {
        // Outbox crash recovery: reset orphaned SENDING entries on startup
        scope.launch {
            try {
                val recovered = recoverOrphanedOutbox()
                if (recovered > 0) {
                    Log.i(TAG, "Outbox crash recovery: reset $recovered orphaned SENDING entries to PENDING")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing outbox crash recovery", e)
            }
        }
        // Listen for incoming messages
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.incomingMessages.collect { (endpointId, message) ->
                try {
                    handleMessage(endpointId, message)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling message ${message::class.simpleName} from $endpointId", e)
                }
            }
        }

        // Listen for incoming file payload transfers (Stage 2)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.incomingFiles.collect { transfer ->
                if (transfer != null) {
                    try {
                        handleIncomingFile(transfer)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling incoming file payload ${transfer.payloadId}", e)
                    }
                }
            }
        }

        // Set up peer connection callbacks
        transport.setOnPeerConnected { peerDeviceId, peerInfo ->
            scope.launch {
                Log.i(TAG, "Peer connected: $peerDeviceId — sending digest")
                syncRepo.recordConnection(peerDeviceId)
                sendDigest(peerInfo.endpointId)
                processPendingOutbox()
            }
        }

        transport.setOnPeerDisconnected { peerDeviceId ->
            scope.launch {
                Log.i(TAG, "Peer disconnected: $peerDeviceId")
                syncRepo.recordDisconnection(peerDeviceId)
                // Zero orphan policy: clean up any buffered pending files from disconnected peer
                val endpoint = transport.getEndpointForDevice(peerDeviceId)
                if (endpoint != null) {
                    val it = pendingFilesByPayloadId.entries.iterator()
                    while (it.hasNext()) {
                        val entry = it.next()
                        if (entry.value.endpointId == endpoint) {
                            if (entry.value.file.exists()) {
                                entry.value.file.delete()
                            }
                            it.remove()
                        }
                    }
                }
            }
        }

        // Periodic re-sync
        syncJob = scope.launch {
            while (isActive) {
                delay(SYNC_INTERVAL_MS)
                periodicSync()
            }
        }

        Log.i(TAG, "SyncCoordinator started")
    }

    fun stop() {
        syncJob?.cancel()
        scope.cancel()
        headersByPayloadId.clear()
        pendingFilesByPayloadId.clear()
        Log.i(TAG, "SyncCoordinator stopped")
    }

    /**
     * Called when a new local observation is created.
     * Immediately pushes it to all connected peers using priority scheduling.
     */
    suspend fun onNewLocalObservation(observation: ObservationEntity) {
        if (observation.missionId != this.missionId) {
            Log.w(TAG, "Ignoring local observation ${observation.eventId}: foreign mission ${observation.missionId} != this $missionId")
            return
        }
        val peers = transport.connectedPeers.value

        for ((peerDeviceId, peerInfo) in peers) {
            // Check if already delivered
            if (outboxDao.isDeliveredTo(observation.eventId, peerDeviceId)) continue

            if (!outboxDao.hasPendingEntry(observation.eventId, peerDeviceId)) {
                outboxDao.insert(OutboxEntity(
                    eventId = observation.eventId,
                    targetPeerId = peerDeviceId,
                    priority = scheduler.scoreObservation(observation)
                ))
            }
        }
        processPendingOutbox()
    }

    /**
     * Called when a new local chat message is created.
     * Enqueues into outbox with priority <= 0.60f and drains pending outbox to peers.
     */
    suspend fun onNewLocalChatMessage(chat: ChatMessageEntity) {
        if (chat.missionId != this.missionId) {
            Log.w(TAG, "Ignoring local chat ${chat.messageId}: foreign mission ${chat.missionId} != this $missionId")
            return
        }
        val peers = transport.connectedPeers.value
        val priority = scheduler.scoreChatMessage(chat)

        if (peers.isEmpty()) {
            if (!outboxDao.hasPendingEntry(chat.messageId, null)) {
                outboxDao.insert(
                    OutboxEntity(
                        eventId = chat.messageId,
                        targetPeerId = null,
                        priority = priority,
                        maxHops = chat.relayHopsRemaining,
                        status = "PENDING"
                    )
                )
            }
            chatDao?.updateStatus(chat.messageId, ChatMessageEntity.STATUS_QUEUED)
            return
        }

        for ((peerDeviceId, _) in peers) {
            if (outboxDao.isDeliveredTo(chat.messageId, peerDeviceId)) continue

            if (!outboxDao.hasPendingEntry(chat.messageId, peerDeviceId)) {
                outboxDao.insert(
                    OutboxEntity(
                        eventId = chat.messageId,
                        targetPeerId = peerDeviceId,
                        priority = priority,
                        maxHops = chat.relayHopsRemaining,
                        status = "PENDING"
                    )
                )
            }
        }
        chatDao?.updateStatus(chat.messageId, ChatMessageEntity.STATUS_SENDING)
        processPendingOutbox()
    }

    // --- Message Handlers ---

    private suspend fun handleMessage(endpointId: String, message: SyncMessage) {
        when (message) {
            is SyncMessage.Hello -> {
                // Already handled by MeshTransport for peer registration
                // But we use this to trigger digest exchange
                sendDigest(endpointId)
            }

            is SyncMessage.StateDigest -> {
                handleDigest(endpointId, message)
            }

            is SyncMessage.EventRequest -> {
                handleEventRequest(endpointId, message)
            }

            is SyncMessage.EventBatch -> {
                handleEventBatch(endpointId, message)
            }

            is SyncMessage.EventAck -> {
                val peerDevice = transport.connectedPeers.value.values.find { it.endpointId == endpointId }?.deviceId
                    ?: if (transport.getEndpointForDevice(message.deviceId) == endpointId) message.deviceId else null
                if (peerDevice == message.deviceId || endpointId.isEmpty() || transport.connectedPeers.value.isEmpty()) handleEventAck(message)
                else Log.w(TAG, "Rejected ACK identity mismatch from $endpointId")
            }

            is SyncMessage.ChatMessage -> {
                handleChatMessage(endpointId, message)
            }

            is SyncMessage.ChatAck -> {
                val peerDevice = transport.connectedPeers.value.values.find { it.endpointId == endpointId }?.deviceId
                    ?: if (transport.getEndpointForDevice(message.deviceId) == endpointId) message.deviceId else null
                if (peerDevice == message.deviceId || endpointId.isEmpty() || transport.connectedPeers.value.isEmpty()) {
                    handleChatAck(message)
                } else {
                    Log.w(TAG, "Rejected Chat ACK identity mismatch from $endpointId")
                }
            }

            is SyncMessage.EvidenceRequest -> {
                handleEvidenceRequest(endpointId, message)
            }

            is SyncMessage.EvidenceHeader -> {
                handleEvidenceHeader(endpointId, message)
            }

            is SyncMessage.EvidenceAck -> {
                handleEvidenceAck(endpointId, message)
            }
        }
    }

    suspend fun sendDigest(endpointId: String) {
        val digests = observationDao.getAllSequenceDigests()
        val sequenceMap = digests.associate { it.authorDeviceId to it.maxSeq }

        val digest = SyncMessage.StateDigest(
            deviceId = deviceId,
            sequences = sequenceMap
        )

        transport.sendMessage(endpointId, digest)
        syncRepo.recordSync(
            peerId = endpointId,
            details = "Sent StateDigest with ${sequenceMap.size} authors"
        )
        Log.d(TAG, "Sent digest with ${sequenceMap.size} authors to $endpointId")
    }

    /**
     * Compare peer's digest with our own:
     * - If peer has higher sequences: send SyncMessage.EventRequest for missing events.
     * - If local has higher sequences: enqueue missing observations for transmission to that peer (and dispatch).
     */
    suspend fun handleDigest(endpointId: String, digest: SyncMessage.StateDigest) {
        Log.d(TAG, "Received digest from ${digest.deviceId}: ${digest.sequences}")

        val ourDigests = observationDao.getAllSequenceDigests()
        val ourSequences = ourDigests.associate { it.authorDeviceId to it.maxSeq }

        syncRepo.recordSync(
            peerId = digest.deviceId,
            details = "Received StateDigest from ${digest.deviceId} with ${digest.sequences.size} authors"
        )

        // 1. Peer has higher sequences: request missing events from peer
        val missingFromAuthors = mutableMapOf<String, Long>()
        for ((authorId, peerMaxSeq) in digest.sequences) {
            val ourMaxSeq = ourSequences[authorId] ?: 0L
            if (peerMaxSeq > ourMaxSeq) {
                missingFromAuthors[authorId] = ourMaxSeq
            }
        }

        if (missingFromAuthors.isNotEmpty()) {
            val eventRequest = SyncMessage.EventRequest(
                requestorDeviceId = deviceId,
                missingFromAuthors = missingFromAuthors
            )
            transport.sendMessage(endpointId, eventRequest)
            syncRepo.recordSync(
                peerId = digest.deviceId,
                details = "Sent EventRequest for ${missingFromAuthors.size} authors: $missingFromAuthors"
            )
            Log.i(TAG, "Sent EventRequest to ${digest.deviceId} ($endpointId) for missing sequences: $missingFromAuthors")
        }

        // 2. Local has higher sequences: enqueue missing observations for transmission to that peer
        val eventsToSend = mutableListOf<ObservationEntity>()

        for ((authorId, ourMaxSeq) in ourSequences) {
            val peerMaxSeq = digest.sequences[authorId] ?: 0L
            if (ourMaxSeq > peerMaxSeq) {
                val missing = observationDao.getAfterSequence(authorId, peerMaxSeq)
                eventsToSend.addAll(missing.filter { it.missionId == this.missionId })
            }
        }

        if (eventsToSend.isEmpty()) {
            Log.d(TAG, "Peer ${digest.deviceId} is up to date on our local observations")
            return
        }

        // Score and sort by priority (Value-of-Information)
        val scored = eventsToSend.map { obs ->
            obs to scheduler.scoreObservation(obs)
        }.sortedByDescending { it.second }

        // Commit to Outbox table BEFORE transmitting over mesh (Durable Local-First Persistence)
        for ((obs, priority) in scored) {
            if (!outboxDao.hasPendingEntry(obs.eventId, digest.deviceId) && !outboxDao.isDeliveredTo(obs.eventId, digest.deviceId)) {
                outboxDao.insert(
                    OutboxEntity(
                        eventId = obs.eventId,
                        targetPeerId = digest.deviceId,
                        priority = priority,
                        schedulerType = "VOI",
                        status = "PENDING"
                    )
                )
            }
        }

        processPendingOutbox()

        // Record current pending queue depth metric
        val queueDepth = outboxDao.getPendingForPeer(digest.deviceId).size
        syncRepo.recordOutboxQueueDepth(queueDepth)

        Log.i(TAG, "Enqueued and dispatched ${eventsToSend.size} missing events to ${digest.deviceId}")
    }

    suspend fun handleEventRequest(endpointId: String, request: SyncMessage.EventRequest) {
        val eventsToSend = mutableListOf<ObservationEntity>()

        for ((authorId, afterSeq) in request.missingFromAuthors) {
            val missing = observationDao.getAfterSequence(authorId, afterSeq)
            eventsToSend.addAll(missing.filter { it.missionId == this.missionId })
        }

        if (eventsToSend.isEmpty()) return

        // Score and sort by priority
        val scored = eventsToSend.map { obs ->
            obs to scheduler.scoreObservation(obs)
        }.sortedByDescending { it.second }

        // Commit to Outbox table BEFORE transmitting over mesh (Durable Local-First Persistence)
        for ((obs, priority) in scored) {
            if (!outboxDao.hasPendingEntry(obs.eventId, request.requestorDeviceId) && !outboxDao.isDeliveredTo(obs.eventId, request.requestorDeviceId)) {
                outboxDao.insert(
                    OutboxEntity(
                        eventId = obs.eventId,
                        targetPeerId = request.requestorDeviceId,
                        priority = priority,
                        schedulerType = "VOI",
                        status = "PENDING"
                    )
                )
            }
        }

        processPendingOutbox()

        syncRepo.recordSync(
            peerId = request.requestorDeviceId,
            details = "Responded to EventRequest with ${eventsToSend.size} events"
        )
        Log.i(TAG, "Responded to event request with ${eventsToSend.size} events")
    }

    /**
     * Receive a batch of events from a peer.
     * - Duplicate suppression via eventId
     * - Store new events
     * - Forward to other peers (relay) if hops remaining
     * - Send ACK back for all batch events to resolve sender outbox
     */
    suspend fun handleEventBatch(endpointId: String, batch: SyncMessage.EventBatch): SyncMessage.EventAck {
        val newEventIds = mutableListOf<String>()
        val acceptedEvents = mutableListOf<SyncObservation>()
        val receivedAt = System.currentTimeMillis()
        val totalBytes = transport.getBytesSent(batch)
        syncRepo.recordBytesReceived(batch.senderDeviceId, totalBytes)

        for (syncObs in batch.events) {
            // Mission isolation validation: foreign mission observations are dropped immediately
            if (syncObs.missionId != this.missionId) {
                Log.w(TAG, "Dropped observation ${syncObs.eventId}: foreign mission ${syncObs.missionId} != this $missionId")
                continue
            }
            acceptedEvents.add(syncObs)

            // Duplicate suppression
            val alreadyExists = observationDao.exists(syncObs.eventId)
            val observation = if (alreadyExists) {
                observationDao.getById(syncObs.eventId)
            } else {
                val newObs = syncToObservation(syncObs, receivedAt)
                observationDao.insert(newObs)
                newEventIds.add(syncObs.eventId)

                // Bridge to MapFusionEngine for conflict detection and edge state update
                mapFusionEngine?.let { engine ->
                    val fusionResult = engine.applyObservation(newObs)
                    if (fusionResult != null) {
                        if (fusionResult.isConflict) {
                            Log.w(TAG, "Map conflict detected on edge ${fusionResult.edgeId}: ${fusionResult.conflictingIds}")
                        } else {
                            Log.d(TAG, "Edge ${fusionResult.edgeId} updated to ${fusionResult.newStatus}")
                        }
                    }
                }

                // Record receive metric
                syncRepo.recordReceive(syncObs.eventId, batch.senderDeviceId, totalBytes / maxOf(1, batch.events.size))

                // Relay to other connected peers if hops remaining
                if (syncObs.relayHopsRemaining > 0) {
                    relayToOtherPeers(newObs, endpointId)
                }
                newObs
            }

            // Stage 2 On-Demand Evidence Transfer:
            // When receiving a SyncObservation with evidenceHash != null: check if local observation
            // has evidencePath != null and file exists. If missing, queue or send SyncMessage.EvidenceRequest
            if (!syncObs.evidenceHash.isNullOrBlank()) {
                val hasLocalFile = observation?.evidencePath?.let { path ->
                    val f = File(path)
                    f.exists() && f.isFile && f.length() > 0L
                } ?: false

                if (!hasLocalFile) {
                    val targetEndpoint = if (endpointId.isNotEmpty()) endpointId
                        else transport.getEndpointForDevice(batch.senderDeviceId)
                    if (targetEndpoint != null && targetEndpoint.isNotEmpty()) {
                        val evidenceReq = SyncMessage.EvidenceRequest(
                            missionId = this.missionId,
                            eventId = syncObs.eventId,
                            evidenceHash = syncObs.evidenceHash
                        )
                        transport.sendMessage(targetEndpoint, evidenceReq)
                        Log.i(TAG, "Requested missing evidence for event ${syncObs.eventId} (hash: ${syncObs.evidenceHash}) from $targetEndpoint")
                    }
                }
            }
        }

        // Send ACK back for all accepted events in the batch
        val ack = SyncMessage.EventAck(
            deviceId = deviceId,
            acknowledgedEventIds = acceptedEvents.map { it.eventId }
        )
        if (endpointId.isNotEmpty() && acceptedEvents.isNotEmpty()) {
            transport.sendMessage(endpointId, ack)
        }

        Log.i(TAG, "Received batch from ${batch.senderDeviceId}: stored ${newEventIds.size}/${batch.events.size} new events, ACK sent for ${ack.acknowledgedEventIds.size}")
        return ack
    }

    suspend fun handleEventBatch(batch: SyncMessage.EventBatch): SyncMessage.EventAck {
        val endpointId = transport.connectedPeers.value.values
            .find { it.deviceId == batch.senderDeviceId }?.endpointId ?: ""
        return handleEventBatch(endpointId, batch)
    }

    suspend fun handleEventAck(ack: SyncMessage.EventAck) {
        val now = System.currentTimeMillis()
        for (eventId in ack.acknowledgedEventIds) {
            if (!outboxDao.hasPendingEntry(eventId, ack.deviceId)) continue
            // Record delivery receipt
            outboxDao.insertReceipt(
                DeliveryReceiptEntity(
                    eventId = eventId,
                    peerDeviceId = ack.deviceId,
                    deliveredAt = now
                )
            )

            // Update outbox status to DELIVERED
            outboxDao.updateStatusForPeer(eventId, ack.deviceId, "DELIVERED", now)

            // Calculate delivery latency
            val observation = observationDao.getById(eventId)
            if (observation != null) {
                val latency = now - observation.observedAtDevice
                syncRepo.recordDelivery(eventId, ack.deviceId, latency)
            }
        }
        Log.d(TAG, "ACK received for ${ack.acknowledgedEventIds.size} events from ${ack.deviceId}")
    }

    // --- Chat Handling ---

    /**
     * Handle incoming ChatMessage:
     * 1. Validate mission boundary. Foreign mission chat is dropped.
     * 2. Deduplicate via chatDao.exists(messageId).
     * 3. If new, commit to Room with senderRole = UNVERIFIED_PARTICIPANT (Astra G3-S13).
     * 4. Relay to other connected peers if relayHopsRemaining > 1.
     * 5. Dispatch ChatAck back to the sender endpoint.
     */
    suspend fun handleChatMessage(endpointId: String, message: SyncMessage.ChatMessage): SyncMessage.ChatAck {
        val totalBytes = transport.getBytesSent(message)
        syncRepo.recordBytesReceived(message.senderDeviceId, totalBytes)

        if (message.missionId != this.missionId) {
            Log.w(TAG, "Dropped chat message ${message.messageId}: foreign mission ${message.missionId} != this $missionId")
            return SyncMessage.ChatAck(deviceId = deviceId, acknowledgedMessageIds = emptyList())
        }

        val alreadyExists = (chatDao?.exists(message.messageId) ?: 0) > 0
        if (!alreadyExists) {
            // Astra G3-S13 Mitigation: Remote senderRole forced to UNVERIFIED_PARTICIPANT
            val entity = ChatMessageEntity(
                messageId = message.messageId,
                missionId = message.missionId,
                senderId = message.senderDeviceId,
                senderName = message.senderName,
                text = message.text.take(ChatMessageEntity.MAX_TEXT_LENGTH),
                timestamp = message.timestamp,
                status = ChatMessageEntity.STATUS_RECEIVED,
                ackPeerCount = 0,
                isLocal = false,
                senderRole = ChatMessageEntity.ROLE_UNVERIFIED,
                linkedEventId = message.linkedEventId,
                authorSequence = message.authorSequence,
                relayHopsRemaining = message.relayHopsRemaining
            )
            chatDao?.insert(entity)

            // Relay to other connected peers if hops remaining > 1
            if (message.relayHopsRemaining > 1) {
                relayChatToOtherPeers(entity, endpointId)
            }
        }

        val ack = SyncMessage.ChatAck(
            deviceId = deviceId,
            acknowledgedMessageIds = listOf(message.messageId)
        )
        if (endpointId.isNotEmpty()) {
            transport.sendMessage(endpointId, ack)
        }

        Log.i(TAG, "Handled chat message ${message.messageId} from ${message.senderDeviceId}, exists=$alreadyExists")
        return ack
    }

    suspend fun handleChatMessage(message: SyncMessage.ChatMessage): SyncMessage.ChatAck {
        val endpointId = transport.connectedPeers.value.values
            .find { it.deviceId == message.senderDeviceId }?.endpointId ?: ""
        return handleChatMessage(endpointId, message)
    }

    private suspend fun relayChatToOtherPeers(chat: ChatMessageEntity, sourceEndpointId: String) {
        val otherPeers = transport.connectedPeers.value.values
            .filter { it.endpointId != sourceEndpointId }

        for (peer in otherPeers) {
            if (peer.deviceId == chat.senderId) continue
            if (outboxDao.isDeliveredTo(chat.messageId, peer.deviceId)) continue

            if (!outboxDao.hasPendingEntry(chat.messageId, peer.deviceId)) {
                outboxDao.insert(
                    OutboxEntity(
                        eventId = chat.messageId,
                        targetPeerId = peer.deviceId,
                        priority = scheduler.scoreChatMessage(chat),
                        maxHops = chat.relayHopsRemaining - 1,
                        status = "PENDING"
                    )
                )
            }
        }
        processPendingOutbox()
    }

    /**
     * Handle incoming ChatAck:
     * Astra G3-S13 Mitigation:
     * Records unique delivery receipts per (messageId, peerDeviceId) in DeliveryReceiptEntity.
     * Duplicate or replayed ACKs cannot inflate ackPeerCount.
     */
    suspend fun handleChatAck(ack: SyncMessage.ChatAck) {
        val now = System.currentTimeMillis()
        for (messageId in ack.acknowledgedMessageIds) {
            val alreadyDelivered = outboxDao.isDeliveredTo(messageId, ack.deviceId)
            if (!alreadyDelivered) {
                outboxDao.insertReceipt(
                    DeliveryReceiptEntity(
                        eventId = messageId,
                        peerDeviceId = ack.deviceId,
                        deliveredAt = now
                    )
                )
            }

            outboxDao.updateStatusForPeer(messageId, ack.deviceId, "DELIVERED", now)

            val receipts = outboxDao.getReceipts(messageId)
            val uniquePeerCount = receipts.map { it.peerDeviceId }.distinct().size

            val chat = chatDao?.getMessage(messageId)
            if (chat != null) {
                val newStatus = if (chat.status == ChatMessageEntity.STATUS_SENDING || chat.status == ChatMessageEntity.STATUS_QUEUED) {
                    ChatMessageEntity.STATUS_SENT_TO_PEERS
                } else {
                    chat.status
                }
                chatDao?.updateAckCount(messageId, uniquePeerCount, newStatus)
            }
        }
        Log.d(TAG, "Chat ACK received for ${ack.acknowledgedMessageIds.size} messages from ${ack.deviceId}")
    }

    // --- Relay ---

    private suspend fun relayToOtherPeers(observation: ObservationEntity, sourceEndpointId: String) {
        val otherPeers = transport.connectedPeers.value.values
            .filter { it.endpointId != sourceEndpointId }

        for (peer in otherPeers) {
            // Don't relay back to the original author
            if (peer.deviceId == observation.authorDeviceId) continue

            // Check if already delivered
            if (outboxDao.isDeliveredTo(observation.eventId, peer.deviceId)) continue

            // Commit to local Outbox BEFORE transmitting over mesh (Durable Local-First Persistence)
            if (!outboxDao.hasPendingEntry(observation.eventId, peer.deviceId)) outboxDao.insert(
                OutboxEntity(
                    eventId = observation.eventId,
                    targetPeerId = peer.deviceId,
                    priority = scheduler.scoreObservation(observation),
                    maxHops = observation.relayHopsRemaining - 1,
                    status = "PENDING"
                )
            )

        }
        processPendingOutbox()
    }

    /**
     * Drains pending outbox entries eligible for transmission under backoff rules.
     * Enforces the Absolute Safety Override (P >= 0.80).
     * Supports dual-resolution for both observations and chat messages.
     */
    suspend fun processPendingOutbox(now: Long = System.currentTimeMillis()): Int = outboxMutex.withLock {
        outboxDao.requeueExpiredSending(now)

        val peers = transport.connectedPeers.value
        if (peers.isNotEmpty()) {
            val broadcastEntries = outboxDao.getPendingByPriority().filter { it.targetPeerId == null }
            for (b in broadcastEntries) {
                outboxDao.expandBroadcast(b, peers.values.map { it.deviceId }, b.priority, now)
            }
        }

        val pending = outboxDao.getPendingByPriority()
        val transmittable = filterTransmittable(pending, now)

        var transmittedCount = 0

        for (entry in transmittable) {
            val targets = if (entry.targetPeerId != null) {
                val peer = peers[entry.targetPeerId]
                if (peer != null) {
                    listOf(peer)
                } else {
                    val endpoint = transport.getEndpointForDevice(entry.targetPeerId)
                    if (endpoint != null) {
                        listOf(PeerInfo(entry.targetPeerId, endpoint, entry.targetPeerId))
                    } else {
                        emptyList()
                    }
                }
            } else {
                peers.values.toList()
            }

            if (targets.isEmpty()) {
                continue
            }

            if (entry.targetPeerId != null && outboxDao.isDeliveredTo(entry.eventId, entry.targetPeerId)) {
                outboxDao.updateStatus(entry.id, "DELIVERED", now)
                continue
            }

            // Dual-resolution: Check ObservationDao first; if null, check ChatDao
            val observation = observationDao.getById(entry.eventId)
            val chatMessage = if (observation == null) chatDao?.getMessage(entry.eventId) else null
            if (observation == null && chatMessage == null) {
                outboxDao.updateStatus(entry.id, "FAILED", now)
                continue
            }

            if (outboxDao.claimSending(entry.id, now, now + ACK_TIMEOUT_MS) == 0) {
                continue
            }

            val syncMessage: SyncMessage = if (observation != null) {
                val syncObs = observationToSync(observation)
                SyncMessage.EventBatch(
                    senderDeviceId = deviceId,
                    events = listOf(syncObs)
                )
            } else {
                val chat = chatMessage!!
                SyncMessage.ChatMessage(
                    messageId = chat.messageId,
                    missionId = chat.missionId,
                    senderDeviceId = chat.senderId,
                    senderName = chat.senderName,
                    text = chat.text,
                    timestamp = chat.timestamp,
                    authorSequence = chat.authorSequence,
                    senderRole = chat.senderRole,
                    linkedEventId = chat.linkedEventId,
                    relayHopsRemaining = minOf(entry.maxHops, chat.relayHopsRemaining)
                )
            }

            var anySent = false
            for (target in targets) {
                if (outboxDao.isDeliveredTo(entry.eventId, target.deviceId)) continue
                val sent = transport.sendMessage(target.endpointId, syncMessage)
                if (sent) {
                    anySent = true
                    val bytesSent = transport.getBytesSent(syncMessage)
                    syncRepo.recordSend(entry.eventId, target.deviceId, bytesSent, entry.schedulerType, entry.priority)
                }
            }

            if (anySent) {
                if (chatMessage != null && (chatMessage.status == ChatMessageEntity.STATUS_QUEUED || chatMessage.status == ChatMessageEntity.STATUS_SENDING)) {
                    chatDao?.updateStatus(chatMessage.messageId, ChatMessageEntity.STATUS_SENDING)
                }
                transmittedCount++
            } else {
                val newRetry = entry.retryCount + 1
                val backoffMs = calculateBackoff(newRetry)
                val nextAttempt = if (entry.priority >= SAFETY_PRIORITY_FLOOR) now else now + backoffMs
                val status = if (newRetry >= entry.maxRetries) "FAILED" else "PENDING"
                outboxDao.updateRetry(entry.id, status, newRetry, nextAttempt, now)
            }
        }

        transmittedCount
    }

    // --- Periodic Sync ---

    private suspend fun periodicSync() {
        val peers = transport.connectedPeers.value
        if (peers.isEmpty()) return

        for ((_, peerInfo) in peers) {
            sendDigest(peerInfo.endpointId)
        }
        processPendingOutbox()
    }

    // --- Conversion Helpers ---

    private fun observationToSync(obs: ObservationEntity): SyncObservation {
        return SyncObservation(
            eventId = obs.eventId,
            missionId = obs.missionId,
            authorDeviceId = obs.authorDeviceId,
            authorSequence = obs.authorSequence,
            eventType = obs.eventType,
            title = obs.title,
            description = obs.description,
            reportedStatus = obs.reportedStatus,
            sourceType = obs.sourceType,
            latitude = obs.latitude,
            longitude = obs.longitude,
            accuracy = obs.accuracy,
            mapEdgeId = obs.mapEdgeId,
            mapNodeId = obs.mapNodeId,
            evidenceHash = obs.evidenceHash,
            verificationStatus = obs.verificationStatus,
            observedAtDevice = obs.observedAtDevice,
            relayHopsRemaining = obs.relayHopsRemaining
        )
    }

    private fun syncToObservation(sync: SyncObservation, receivedAt: Long): ObservationEntity {
        return ObservationEntity(
            eventId = sync.eventId,
            missionId = sync.missionId,
            authorDeviceId = sync.authorDeviceId,
            authorSequence = sync.authorSequence,
            eventType = sync.eventType,
            title = sync.title,
            description = sync.description,
            reportedStatus = sync.reportedStatus,
            sourceType = sync.sourceType,
            latitude = sync.latitude,
            longitude = sync.longitude,
            accuracy = sync.accuracy,
            mapEdgeId = sync.mapEdgeId,
            mapNodeId = sync.mapNodeId,
            evidencePath = null,
            evidenceHash = sync.evidenceHash,
            verificationStatus = sync.verificationStatus,
            observedAtDevice = sync.observedAtDevice,
            receivedAt = receivedAt,
            isLocal = false,
            relayHopsRemaining = sync.relayHopsRemaining
        )
    }

    // --- Stage 2 On-Demand Evidence Transfer ---

    suspend fun handleEvidenceRequest(endpointId: String, request: SyncMessage.EvidenceRequest) {
        if (request.missionId != this.missionId) {
            Log.w(TAG, "Rejected EvidenceRequest for ${request.eventId}: foreign mission ${request.missionId} != this $missionId")
            return
        }

        // Absolute SOS Priority Invariant:
        // Urgent packets (P >= 0.80) strictly preempt photo transfers
        val pending = outboxDao.getPendingByPriority()
        if (pending.any { it.priority >= SAFETY_PRIORITY_FLOOR }) {
            Log.i(TAG, "Urgent outbox messages (P >= $SAFETY_PRIORITY_FLOOR) waiting. Draining outbox before serving evidence request.")
            processPendingOutbox()
        }

        val observation = observationDao.getById(request.eventId)
        if (observation == null || observation.missionId != this.missionId) {
            Log.w(TAG, "Rejected EvidenceRequest for ${request.eventId}: observation not found or foreign mission (${observation?.missionId} != $missionId)")
            return
        }
        val evidencePath = observation.evidencePath
        val file = if (evidencePath != null) File(evidencePath) else null

        if (file != null && file.exists() && file.isFile && file.length() > 0L) {
            val payloadId = transport.sendFilePayload(endpointId, file)
            if (payloadId != null) {
                val header = SyncMessage.EvidenceHeader(
                    missionId = this.missionId,
                    eventId = request.eventId,
                    evidenceHash = request.evidenceHash,
                    payloadId = payloadId,
                    fileSizeBytes = file.length()
                )
                transport.sendMessage(endpointId, header)
                Log.i(TAG, "Dispatched EvidenceHeader and file payload ($payloadId, ${file.length()} bytes) for event ${request.eventId} to $endpointId")
            } else {
                Log.e(TAG, "Failed to send file payload for event ${request.eventId} to $endpointId")
            }
        } else {
            Log.w(TAG, "Cannot serve EvidenceRequest: observation file not found or empty on disk for event ${request.eventId} ($evidencePath)")
        }
    }

    suspend fun handleEvidenceHeader(endpointId: String, header: SyncMessage.EvidenceHeader) {
        if (header.missionId != this.missionId) {
            Log.w(TAG, "Rejected EvidenceHeader for event ${header.eventId}: foreign mission ${header.missionId} != this $missionId")
            return
        }

        val observation = observationDao.getById(header.eventId)
        if (observation == null || observation.missionId != this.missionId) {
            Log.w(TAG, "Rejected EvidenceHeader for event ${header.eventId}: observation not found or foreign mission (${observation?.missionId} != $missionId)")
            return
        }

        Log.d(TAG, "Received EvidenceHeader for event ${header.eventId}, payload ${header.payloadId}, hash ${header.evidenceHash}")
        val pendingTransfer = pendingFilesByPayloadId.remove(header.payloadId)
        if (pendingTransfer != null && pendingTransfer.isComplete) {
            verifyAndStoreEvidence(endpointId, header, pendingTransfer.file)
        } else {
            headersByPayloadId[header.payloadId] = header
        }
    }

    suspend fun handleIncomingFile(transfer: IncomingFileTransfer) {
        val payloadId = transfer.payloadId
        val endpointId = transfer.endpointId
        val tempFile = transfer.file

        if (transfer.isFailed) {
            Log.w(TAG, "Incoming file transfer failed for payload $payloadId from $endpointId")
            if (tempFile.exists()) {
                tempFile.delete()
            }
            val header = headersByPayloadId.remove(payloadId)
            if (header != null) {
                observationDao.updateEvidence(header.eventId, null, "CORRUPTED")
                val ack = SyncMessage.EvidenceAck(
                    missionId = this.missionId,
                    eventId = header.eventId,
                    evidenceHash = header.evidenceHash,
                    verified = false
                )
                transport.sendMessage(endpointId, ack)
            }
            pendingFilesByPayloadId.remove(payloadId)
            return
        }

        if (transfer.isComplete) {
            val header = headersByPayloadId.remove(payloadId)
            if (header == null) {
                Log.d(TAG, "File payload $payloadId completed before EvidenceHeader received. Buffering...")
                pendingFilesByPayloadId[payloadId] = transfer
            } else {
                verifyAndStoreEvidence(endpointId, header, tempFile)
            }
        }
    }

    suspend fun verifyAndStoreEvidence(endpointId: String, header: SyncMessage.EvidenceHeader, tempFile: File) {
        if (!tempFile.exists() || !tempFile.isFile || tempFile.length() == 0L) {
            Log.w(TAG, "Evidence file empty or missing for event ${header.eventId}, payload ${header.payloadId}")
            if (tempFile.exists()) tempFile.delete()
            observationDao.updateEvidence(header.eventId, null, "CORRUPTED")
            val ack = SyncMessage.EvidenceAck(
                missionId = this.missionId,
                eventId = header.eventId,
                evidenceHash = header.evidenceHash,
                verified = false
            )
            transport.sendMessage(endpointId, ack)
            return
        }

        val computedHash = computeSha256(tempFile)
        if (computedHash.equals(header.evidenceHash, ignoreCase = true)) {
            if (!evidenceDirectory.exists()) {
                evidenceDirectory.mkdirs()
            }
            val destinationFile = File(evidenceDirectory, "evidence_${header.eventId}_${header.evidenceHash.take(8)}.jpg")
            try {
                tempFile.copyTo(destinationFile, overwrite = true)
                tempFile.delete()
                observationDao.updateEvidence(header.eventId, destinationFile.absolutePath, "VERIFIED")
                val ack = SyncMessage.EvidenceAck(
                    missionId = this.missionId,
                    eventId = header.eventId,
                    evidenceHash = header.evidenceHash,
                    verified = true
                )
                transport.sendMessage(endpointId, ack)
                Log.i(TAG, "Evidence verified and committed: ${destinationFile.absolutePath} (event ${header.eventId})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy verified evidence file to ${destinationFile.absolutePath}", e)
                observationDao.updateEvidence(header.eventId, null, "CORRUPTED")
                val ack = SyncMessage.EvidenceAck(
                    missionId = this.missionId,
                    eventId = header.eventId,
                    evidenceHash = header.evidenceHash,
                    verified = false
                )
                transport.sendMessage(endpointId, ack)
            }
        } else {
            Log.w(TAG, "Evidence SHA-256 mismatch for event ${header.eventId}: expected ${header.evidenceHash}, computed $computedHash")
            if (tempFile.exists()) {
                tempFile.delete()
            }
            observationDao.updateEvidence(header.eventId, null, "CORRUPTED")
            val ack = SyncMessage.EvidenceAck(
                missionId = this.missionId,
                eventId = header.eventId,
                evidenceHash = header.evidenceHash,
                verified = false
            )
            transport.sendMessage(endpointId, ack)
        }
    }

    suspend fun handleEvidenceAck(endpointId: String, ack: SyncMessage.EvidenceAck) {
        if (ack.missionId != this.missionId) {
            Log.w(TAG, "Rejected EvidenceAck for ${ack.eventId}: foreign mission ${ack.missionId} != this $missionId")
            return
        }
        Log.i(TAG, "Received EvidenceAck from $endpointId for event ${ack.eventId}: verified=${ack.verified}")
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
}
