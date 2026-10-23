package com.rescuemesh.ai.simulation

import com.rescuemesh.ai.data.entity.DeliveryReceiptEntity
import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.data.entity.OutboxEntity
import com.rescuemesh.ai.data.repository.ObservationRepository
import com.rescuemesh.ai.data.repository.SyncRepository
import com.rescuemesh.ai.fakes.FakeMapDao
import com.rescuemesh.ai.fakes.FakeMetricsDao
import com.rescuemesh.ai.fakes.FakeObservationDao
import com.rescuemesh.ai.fakes.FakeOutboxDao
import com.rescuemesh.ai.fakes.FakePeerDao
import com.rescuemesh.ai.intelligence.MapFusionEngine
import com.rescuemesh.ai.intelligence.PriorityScheduler
import com.rescuemesh.ai.intelligence.RoutePlanner
import com.rescuemesh.ai.mesh.SyncCoordinator
import com.rescuemesh.ai.model.SyncMessage
import com.rescuemesh.ai.model.SyncObservation
import java.util.concurrent.atomic.AtomicLong

/**
 * Self-contained headless node container encapsulating isolated thread-safe fake DAOs,
 * PriorityScheduler, MapFusionEngine, RoutePlanner, VirtualMeshTransport, and SyncCoordinator.
 *
 * Adheres strictly to:
 * - Invariant 1: Strictly offline-first (zero external network/cloud calls).
 * - Invariant 2: Durable local-first persistence (committed to Room DAO fakes before transmission).
 * - Invariant 3: Conflict-preserving map fusion (contradictory reports preserved as CONFLICTED with full audit history).
 * - Invariant 4: Routes NEVER described as "safe" in code, test names, assertions, or comments.
 * - Invariant 5: Value-of-Information safety floor (P >= 0.80 for HAZARD and BLOCKED routes).
 */
open class SimulatedRescueNode(
    val nodeId: String,
    val deviceName: String = nodeId,
    val missionId: String = "disaster-mesh-alpha",
    val environment: VirtualMeshEnvironment? = null
) {
    val observationDao = FakeObservationDao()
    val mapDao = FakeMapDao()
    val outboxDao = FakeOutboxDao()
    val metricsDao = FakeMetricsDao()
    val peerDao = FakePeerDao()

    val observationRepo = ObservationRepository(observationDao, outboxDao, nodeId, missionId)
    val syncRepo = SyncRepository(metricsDao)

    val scheduler = PriorityScheduler(observationDao, mapDao, outboxDao)
    val fusionEngine = MapFusionEngine(mapDao, observationDao)
    val routePlanner = RoutePlanner(mapDao)

    val transport: VirtualMeshTransport = VirtualMeshTransport(
        nodeId = nodeId,
        deviceName = deviceName,
        environment = environment
    )

    val syncCoordinator: SyncCoordinator = SyncCoordinator(
        transport = transport,
        observationDao = observationDao,
        outboxDao = outboxDao,
        observationRepo = observationRepo,
        syncRepo = syncRepo,
        scheduler = scheduler,
        mapFusionEngine = fusionEngine,
        deviceId = nodeId,
        missionId = missionId
    )

    private val sequenceCounter = AtomicLong(0L)
    val totalBytesTransferred = AtomicLong(0L)
    val totalObservationsReceived = AtomicLong(0L)

    fun startSyncCoordinator() {
        transport.startAdvertisingAndDiscovery(deviceName, "RESCUE_NODE")
        syncCoordinator.start()
    }

    fun stopSyncCoordinator() {
        syncCoordinator.stop()
        transport.stop()
    }

    suspend fun createObservation(
        eventType: String,
        title: String,
        reportedStatus: String? = null,
        mapEdgeId: String? = null,
        verificationStatus: String = "UNVERIFIED",
        isLocal: Boolean = true,
        description: String = "Observation logged by $nodeId",
        observedAtDevice: Long = System.currentTimeMillis()
    ): ObservationEntity {
        val seq = sequenceCounter.incrementAndGet()
        val eventId = "obs-$nodeId-$seq"

        val obs = ObservationEntity(
            eventId = eventId,
            missionId = missionId,
            authorDeviceId = nodeId,
            authorSequence = seq,
            eventType = eventType,
            title = title,
            description = description,
            reportedStatus = reportedStatus,
            verificationStatus = verificationStatus,
            mapEdgeId = mapEdgeId,
            isLocal = isLocal,
            observedAtDevice = observedAtDevice
        )

        // Invariant 2: Durable Local-First Persistence - commit locally before transmission
        observationDao.insert(obs)

        // Queue in Outbox with scheduler score
        val initialPriority = scheduler.scoreObservation(obs)
        outboxDao.insert(
            OutboxEntity(
                eventId = eventId,
                priority = initialPriority,
                status = "PENDING"
            )
        )

        // Invariant 3: Apply locally to map fusion engine if edge observation
        if (mapEdgeId != null) {
            fusionEngine.applyObservation(obs)
        }

        return obs
    }

    suspend fun generateStateDigest(): SyncMessage.StateDigest {
        val digests = observationDao.getAllSequenceDigests()
        val seqMap = digests.associate { it.authorDeviceId to it.maxSeq }
        return SyncMessage.StateDigest(deviceId = nodeId, sequences = seqMap)
    }

    suspend fun selectEventsToSend(schedulerType: String, byteBudget: Int): List<ObservationEntity> {
        return when (schedulerType.uppercase()) {
            "VOI" -> {
                val selectedPairs = scheduler.selectWithinBudget(byteBudget)
                val result = mutableListOf<ObservationEntity>()
                for ((eventId, _) in selectedPairs) {
                    val obs = observationDao.getById(eventId)
                    if (obs != null) result.add(obs)
                }
                result
            }
            "FIFO" -> {
                val pending = outboxDao.getPendingByFifo()
                val result = mutableListOf<ObservationEntity>()
                var usedBytes = 0
                for (entry in pending) {
                    val obs = observationDao.getById(entry.eventId) ?: continue
                    val estimatedBytes = (obs.title.length + obs.description.length + 200) * 2
                    if (usedBytes + estimatedBytes <= byteBudget) {
                        result.add(obs)
                        usedBytes += estimatedBytes
                    } else if (result.isEmpty()) {
                        result.add(obs)
                        break
                    }
                }
                result
            }
            "RANDOM" -> {
                val pending = outboxDao.getPendingByFifo().shuffled()
                val result = mutableListOf<ObservationEntity>()
                var usedBytes = 0
                for (entry in pending) {
                    val obs = observationDao.getById(entry.eventId) ?: continue
                    val estimatedBytes = (obs.title.length + obs.description.length + 200) * 2
                    if (usedBytes + estimatedBytes <= byteBudget) {
                        result.add(obs)
                        usedBytes += estimatedBytes
                    } else if (result.isEmpty()) {
                        result.add(obs)
                        break
                    }
                }
                result
            }
            else -> emptyList()
        }
    }

    suspend fun receiveMessage(fromNodeId: String, message: SyncMessage): List<SyncMessage> {
        val responses = mutableListOf<SyncMessage>()

        when (message) {
            is SyncMessage.Hello -> {
                responses.add(generateStateDigest())
            }
            is SyncMessage.StateDigest -> {
                val ourDigests = observationDao.getAllSequenceDigests()
                val ourSeqMap = ourDigests.associate { it.authorDeviceId to it.maxSeq }

                val missingEvents = mutableListOf<ObservationEntity>()
                for ((authorId, ourMax) in ourSeqMap) {
                    val peerMax = message.sequences[authorId] ?: 0L
                    if (ourMax > peerMax) {
                        val events = observationDao.getAfterSequence(authorId, peerMax)
                        missingEvents.addAll(events)
                    }
                }

                if (missingEvents.isNotEmpty()) {
                    val syncObsList = missingEvents.map { observationToSync(it) }
                    responses.add(
                        SyncMessage.EventBatch(
                            senderDeviceId = nodeId,
                            events = syncObsList
                        )
                    )
                }
            }
            is SyncMessage.EventBatch -> {
                val newEventIds = mutableListOf<String>()
                val now = System.currentTimeMillis()

                for (syncObs in message.events) {
                    if (observationDao.exists(syncObs.eventId)) continue

                    val obs = syncToObservation(syncObs, now)
                    observationDao.insert(obs)
                    newEventIds.add(obs.eventId)
                    totalObservationsReceived.incrementAndGet()

                    if (obs.mapEdgeId != null) {
                        fusionEngine.applyObservation(obs)
                    }
                }

                if (newEventIds.isNotEmpty()) {
                    responses.add(
                        SyncMessage.EventAck(
                            deviceId = nodeId,
                            acknowledgedEventIds = newEventIds
                        )
                    )
                }
            }
            is SyncMessage.EventAck -> {
                val now = System.currentTimeMillis()
                for (eventId in message.acknowledgedEventIds) {
                    outboxDao.insertReceipt(
                        DeliveryReceiptEntity(
                            eventId = eventId,
                            peerDeviceId = message.deviceId,
                            deliveredAt = now
                        )
                    )
                    outboxDao.updateStatusByEvent(eventId, "DELIVERED", now)
                }
            }
            is SyncMessage.EventRequest -> {
                val requested = mutableListOf<ObservationEntity>()
                for ((authorId, afterSeq) in message.missingFromAuthors) {
                    requested.addAll(observationDao.getAfterSequence(authorId, afterSeq))
                }
                if (requested.isNotEmpty()) {
                    responses.add(
                        SyncMessage.EventBatch(
                            senderDeviceId = nodeId,
                            events = requested.map { observationToSync(it) }
                        )
                    )
                }
            }
            else -> {
                // EvidenceRequest, EvidenceHeader, EvidenceAck ignored in virtual node
            }
        }

        return responses
    }

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
