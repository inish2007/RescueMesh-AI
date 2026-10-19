package com.rescuemesh.ai.mesh

import android.util.Log
import com.rescuemesh.ai.data.dao.ObservationDao
import com.rescuemesh.ai.data.dao.OutboxDao
import com.rescuemesh.ai.data.entity.DeliveryReceiptEntity
import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.data.entity.OutboxEntity
import com.rescuemesh.ai.data.repository.ObservationRepository
import com.rescuemesh.ai.data.repository.SyncRepository
import com.rescuemesh.ai.intelligence.PriorityScheduler
import com.rescuemesh.ai.model.SyncMessage
import com.rescuemesh.ai.model.SyncObservation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

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
    private val deviceId: String,
    private val missionId: String
) {
    companion object {
        private const val TAG = "SyncCoordinator"
        private const val SYNC_INTERVAL_MS = 30_000L  // Re-sync every 30 seconds
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null

    fun start() {
        // Listen for incoming messages
        scope.launch {
            transport.incomingMessages.collectLatest { (endpointId, message) ->
                handleMessage(endpointId, message)
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

            val syncObs = observationToSync(observation)
            val batch = SyncMessage.EventBatch(
                senderDeviceId = deviceId,
                events = listOf(syncObs)
            )

            val bytesSent = transport.getBytesSent(batch)
            val sent = transport.sendMessage(peerInfo.endpointId, batch)

            if (sent) {
                val priority = scheduler.scoreObservation(observation)
                syncRepo.recordSend(observation.eventId, peerDeviceId, bytesSent, "VOI", priority)
                syncRepo.recordQueueDecision(
                    observation.eventId, "VOI", priority,
                    scheduler.scoreBreakdown(observation)
                )
            }
        }
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
                handleEventAck(message)
            }
        }
    }

    private suspend fun sendDigest(endpointId: String) {
        val digests = observationDao.getAllSequenceDigests()
        val sequenceMap = digests.associate { it.authorDeviceId to it.maxSeq }

        val digest = SyncMessage.StateDigest(
            deviceId = deviceId,
            sequences = sequenceMap
        )

        transport.sendMessage(endpointId, digest)
        Log.d(TAG, "Sent digest with ${sequenceMap.size} authors to $endpointId")
    }

    /**
     * Compare peer's digest with our own to find what they're missing.
     * Then send the missing events.
     */
    private suspend fun handleDigest(endpointId: String, digest: SyncMessage.StateDigest) {
        Log.d(TAG, "Received digest from ${digest.deviceId}: ${digest.sequences}")

        val ourDigests = observationDao.getAllSequenceDigests()
        val ourSequences = ourDigests.associate { it.authorDeviceId to it.maxSeq }

        // Find events we have that the peer doesn't
        val eventsToSend = mutableListOf<ObservationEntity>()

        for ((authorId, ourMaxSeq) in ourSequences) {
            val peerMaxSeq = digest.sequences[authorId] ?: 0L
            if (ourMaxSeq > peerMaxSeq) {
                val missing = observationDao.getAfterSequence(authorId, peerMaxSeq)
                eventsToSend.addAll(missing)
            }
        }

        if (eventsToSend.isEmpty()) {
            Log.d(TAG, "Peer ${digest.deviceId} is up to date")
            return
        }

        // Score and sort by priority
        val scored = eventsToSend.map { obs ->
            obs to scheduler.scoreObservation(obs)
        }.sortedByDescending { it.second }

        // Send in batches
        val syncObservations = scored.map { (obs, _) -> observationToSync(obs) }
        val batch = SyncMessage.EventBatch(
            senderDeviceId = deviceId,
            events = syncObservations
        )

        val bytesSent = transport.getBytesSent(batch)
        transport.sendMessage(endpointId, batch)

        // Record metrics for each event
        for ((obs, priority) in scored) {
            syncRepo.recordSend(obs.eventId, digest.deviceId, bytesSent / scored.size, "VOI", priority)
            syncRepo.recordQueueDecision(
                obs.eventId, "VOI", priority,
                scheduler.scoreBreakdown(obs)
            )
        }

        Log.i(TAG, "Sent ${eventsToSend.size} missing events to ${digest.deviceId}")
    }

    private suspend fun handleEventRequest(endpointId: String, request: SyncMessage.EventRequest) {
        val eventsToSend = mutableListOf<ObservationEntity>()

        for ((authorId, afterSeq) in request.missingFromAuthors) {
            val missing = observationDao.getAfterSequence(authorId, afterSeq)
            eventsToSend.addAll(missing)
        }

        if (eventsToSend.isEmpty()) return

        val syncObservations = eventsToSend.map { observationToSync(it) }
        val batch = SyncMessage.EventBatch(
            senderDeviceId = deviceId,
            events = syncObservations
        )

        transport.sendMessage(endpointId, batch)
        Log.i(TAG, "Responded to event request with ${eventsToSend.size} events")
    }

    /**
     * Receive a batch of events from a peer.
     * - Duplicate suppression via eventId
     * - Store new events
     * - Forward to other peers (relay) if hops remaining
     * - Send ACK back
     */
    private suspend fun handleEventBatch(endpointId: String, batch: SyncMessage.EventBatch) {
        val newEventIds = mutableListOf<String>()
        val receivedAt = System.currentTimeMillis()

        for (syncObs in batch.events) {
            // Duplicate suppression
            if (observationDao.exists(syncObs.eventId)) continue

            val observation = syncToObservation(syncObs, receivedAt)
            observationDao.insert(observation)
            newEventIds.add(syncObs.eventId)

            // Record receive metric
            syncRepo.recordReceive(syncObs.eventId, batch.senderDeviceId, 0)

            // Relay to other connected peers if hops remaining
            if (syncObs.relayHopsRemaining > 0) {
                relayToOtherPeers(observation, endpointId)
            }
        }

        // Send ACK
        if (newEventIds.isNotEmpty()) {
            val ack = SyncMessage.EventAck(
                deviceId = deviceId,
                acknowledgedEventIds = newEventIds
            )
            transport.sendMessage(endpointId, ack)

            Log.i(TAG, "Received and stored ${newEventIds.size} new events from ${batch.senderDeviceId}")
        }
    }

    private suspend fun handleEventAck(ack: SyncMessage.EventAck) {
        val now = System.currentTimeMillis()
        for (eventId in ack.acknowledgedEventIds) {
            // Record delivery receipt
            outboxDao.insertReceipt(
                DeliveryReceiptEntity(
                    eventId = eventId,
                    peerDeviceId = ack.deviceId,
                    deliveredAt = now
                )
            )

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

            val relayObs = observationToSync(observation).copy(
                relayHopsRemaining = observation.relayHopsRemaining - 1
            )

            val batch = SyncMessage.EventBatch(
                senderDeviceId = deviceId,
                events = listOf(relayObs)
            )

            transport.sendMessage(peer.endpointId, batch)
            Log.d(TAG, "Relayed ${observation.eventId} to ${peer.deviceId}")
        }
    }

    // --- Periodic Sync ---

    private suspend fun periodicSync() {
        val peers = transport.connectedPeers.value
        if (peers.isEmpty()) return

        for ((_, peerInfo) in peers) {
            sendDigest(peerInfo.endpointId)
        }
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
            verificationStatus = sync.verificationStatus,
            observedAtDevice = sync.observedAtDevice,
            receivedAt = receivedAt,
            isLocal = false,
            relayHopsRemaining = sync.relayHopsRemaining
        )
    }
}
