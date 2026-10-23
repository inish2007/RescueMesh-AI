package com.rescuemesh.ai.mesh

import android.util.Log
import com.rescuemesh.ai.data.dao.ObservationDao
import com.rescuemesh.ai.data.dao.OutboxDao
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
 * 5. On new local observation → push to all connected peers
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
    private val missionId: String
) {
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
        scope.launch {
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

        // Set up peer connection callbacks
        transport.setOnPeerConnected { peerDeviceId, peerInfo ->
            scope.launch {
                Log.i(TAG, "Peer connected: $peerDeviceId — sending digest")
                syncRepo.recordConnection(peerDeviceId)
                sendDigest(peerInfo.endpointId)
            }
        }

        transport.setOnPeerDisconnected { peerDeviceId ->
            scope.launch {
                Log.i(TAG, "Peer disconnected: $peerDeviceId")
                syncRepo.recordDisconnection(peerDeviceId)
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
        Log.i(TAG, "SyncCoordinator stopped")
    }

    /**
     * Called when a new local observation is created.
     * Immediately pushes it to all connected peers using priority scheduling.
     */
    suspend fun onNewLocalObservation(observation: ObservationEntity) {
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
                if (peerDevice == message.deviceId) handleEventAck(message)
                else Log.w(TAG, "Rejected ACK identity mismatch from $endpointId")
            }

            is SyncMessage.EvidenceRequest -> {
                // Handled in Stage 2 by Agent A
            }

            is SyncMessage.EvidenceHeader -> {
                // Handled in Stage 2 by Agent A
            }

            is SyncMessage.EvidenceAck -> {
                // Handled in Stage 2 by Agent A
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
                eventsToSend.addAll(missing)
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
            eventsToSend.addAll(missing)
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
        val receivedAt = System.currentTimeMillis()
        val totalBytes = transport.getBytesSent(batch)
        syncRepo.recordBytesReceived(batch.senderDeviceId, totalBytes)

        for (syncObs in batch.events) {
            // Duplicate suppression
            if (observationDao.exists(syncObs.eventId)) continue

            val observation = syncToObservation(syncObs, receivedAt)
            observationDao.insert(observation)
            newEventIds.add(syncObs.eventId)

            // Bridge to MapFusionEngine for conflict detection and edge state update
            mapFusionEngine?.let { engine ->
                val fusionResult = engine.applyObservation(observation)
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
                relayToOtherPeers(observation, endpointId)
            }
        }

        // Send ACK back for all events in the non-empty batch so sender resolves outbox / retries
        val ack = SyncMessage.EventAck(
            deviceId = deviceId,
            acknowledgedEventIds = batch.events.map { it.eventId }
        )
        if (endpointId.isNotEmpty() && batch.events.isNotEmpty()) {
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

            val observation = observationDao.getById(entry.eventId)
            if (observation == null) {
                outboxDao.updateStatus(entry.id, "FAILED", now)
                continue
            }

            if (outboxDao.claimSending(entry.id, now, now + ACK_TIMEOUT_MS) == 0) {
                continue
            }

            val syncObs = observationToSync(observation)
            val batch = SyncMessage.EventBatch(
                senderDeviceId = deviceId,
                events = listOf(syncObs)
            )

            var anySent = false
            for (target in targets) {
                if (outboxDao.isDeliveredTo(entry.eventId, target.deviceId)) continue
                val sent = transport.sendMessage(target.endpointId, batch)
                if (sent) {
                    anySent = true
                    val bytesSent = transport.getBytesSent(batch)
                    syncRepo.recordSend(entry.eventId, target.deviceId, bytesSent, entry.schedulerType, entry.priority)
                }
            }

            if (anySent) {
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
}
