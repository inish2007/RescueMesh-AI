package com.rescuemesh.ai.simulation

import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.model.PeerInfo
import com.rescuemesh.ai.model.SyncMessage
import com.rescuemesh.ai.model.SyncObservation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Headless In-Memory Virtual Mesh Network Environment.
 *
 * Coordinates N >= 5 concurrent simulated rescue nodes exchanging sync protocol messages
 * (Hello, StateDigest, EventBatch, EventAck, EventRequest).
 * Models link latency delays, probabilistic packet loss, dynamic link disconnect/reconnect,
 * network partition lifecycles, and burst deliveries.
 *
 * Adheres strictly to:
 * - Invariant 1: Strictly offline-first (zero external network/cloud calls).
 * - Invariant 2: Durable local-first persistence (committed to Room DAO fakes before transmission).
 * - Invariant 3: Conflict-preserving map fusion (contradictory reports preserved as CONFLICTED with full audit history).
 * - Invariant 4: Routes NEVER described as "safe" in code, test names, assertions, or comments.
 * - Invariant 5: Value-of-Information safety floor (P >= 0.80 for HAZARD and BLOCKED routes).
 */
open class VirtualMeshEnvironment(
    var latencyMs: Long = 0L,
    var packetLossRate: Double = 0.0,
    val byteBudgetPerSyncWindow: Int = Int.MAX_VALUE,
    val defaultMissionId: String = "disaster-mesh-alpha"
) {
    val nodes = ConcurrentHashMap<String, SimulatedRescueNode>()
    val transports = ConcurrentHashMap<String, VirtualMeshTransport>()

    private val brokenLinks = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    private val partitions = CopyOnWriteArrayList<Set<String>>()

    val totalBytesTransferred = AtomicLong(0L)
    val totalPacketsDelivered = AtomicLong(0L)
    val totalPacketsDropped = AtomicLong(0L)

    open fun registerNode(
        nodeId: String,
        deviceName: String = nodeId,
        missionId: String = defaultMissionId
    ): SimulatedRescueNode {
        val node = SimulatedRescueNode(
            nodeId = nodeId,
            deviceName = deviceName,
            missionId = missionId,
            environment = this
        )
        nodes[nodeId] = node
        transports[nodeId] = node.transport
        return node
    }

    fun registerNode(node: SimulatedRescueNode): SimulatedRescueNode {
        nodes[node.nodeId] = node
        transports[node.nodeId] = node.transport
        node.transport.environment = this
        return node
    }

    fun registerNode(nodeId: String, transport: VirtualMeshTransport) {
        transports[nodeId] = transport
        transport.environment = this
    }

    fun registerTransport(nodeId: String, transport: VirtualMeshTransport) {
        transports[nodeId] = transport
    }

    fun unregisterNode(nodeId: String) {
        nodes.remove(nodeId)
        transports.remove(nodeId)?.stop()
    }

    fun getNode(nodeId: String): SimulatedRescueNode? = nodes[nodeId]

    fun disconnectNodes(nodeA: String, nodeB: String) {
        brokenLinks.add(nodeA to nodeB)
        brokenLinks.add(nodeB to nodeA)

        val transportA = transports[nodeA] ?: nodes[nodeA]?.transport
        val transportB = transports[nodeB] ?: nodes[nodeB]?.transport
        transportA?.onPeerDisconnected(nodeB)
        transportB?.onPeerDisconnected(nodeA)
    }

    fun connectNodes(nodeA: String, nodeB: String) {
        brokenLinks.remove(nodeA to nodeB)
        brokenLinks.remove(nodeB to nodeA)

        val transportA = transports[nodeA] ?: nodes[nodeA]?.transport
        val transportB = transports[nodeB] ?: nodes[nodeB]?.transport
        if (transportA != null && transportB != null) {
            val peerB = PeerInfo(deviceId = nodeB, endpointId = "endpoint-$nodeB", displayName = transportB.deviceName)
            val peerA = PeerInfo(deviceId = nodeA, endpointId = "endpoint-$nodeA", displayName = transportA.deviceName)
            transportA.onPeerConnected(nodeB, peerB)
            transportB.onPeerConnected(nodeA, peerA)
        }
    }

    fun disconnectLink(nodeA: String, nodeB: String) = disconnectNodes(nodeA, nodeB)
    fun reconnectLink(nodeA: String, nodeB: String) = connectNodes(nodeA, nodeB)

    fun partitionMesh(groupA: Set<String>, groupB: Set<String>) {
        partitions.clear()
        partitions.add(groupA)
        partitions.add(groupB)

        for (a in groupA) {
            for (b in groupB) {
                val transportA = transports[a] ?: nodes[a]?.transport
                val transportB = transports[b] ?: nodes[b]?.transport
                transportA?.onPeerDisconnected(b)
                transportB?.onPeerDisconnected(a)
            }
        }
    }

    fun healMesh() {
        partitions.clear()
        brokenLinks.clear()

        val allNodeIds = (transports.keys + nodes.keys).distinct()
        for (i in allNodeIds.indices) {
            for (j in (i + 1) until allNodeIds.size) {
                val a = allNodeIds[i]
                val b = allNodeIds[j]
                val transportA = transports[a] ?: nodes[a]?.transport
                val transportB = transports[b] ?: nodes[b]?.transport
                if (transportA != null && transportB != null) {
                    val peerB = PeerInfo(deviceId = b, endpointId = "endpoint-$b", displayName = transportB.deviceName)
                    val peerA = PeerInfo(deviceId = a, endpointId = "endpoint-$a", displayName = transportA.deviceName)
                    transportA.onPeerConnected(b, peerB)
                    transportB.onPeerConnected(a, peerA)
                }
            }
        }
    }

    fun canCommunicate(fromNodeId: String, toNodeId: String): Boolean {
        if (fromNodeId == toNodeId) return true
        if (brokenLinks.contains(fromNodeId to toNodeId)) return false

        if (partitions.isNotEmpty()) {
            val fromPartition = partitions.find { it.contains(fromNodeId) }
            val toPartition = partitions.find { it.contains(toNodeId) }
            if (fromPartition != null && toPartition != null && fromPartition != toPartition) {
                return false
            }
        }
        return true
    }

    suspend fun deliverMessage(fromNodeId: String, toNodeId: String, message: SyncMessage): Boolean {
        if (!canCommunicate(fromNodeId, toNodeId)) {
            totalPacketsDropped.incrementAndGet()
            return false
        }

        if (packetLossRate > 0.0 && Random.nextDouble() < packetLossRate) {
            totalPacketsDropped.incrementAndGet()
            return false
        }

        if (latencyMs > 0L) {
            delay(latencyMs)
        }

        val targetNode = nodes[toNodeId]
        val targetTransport = transports[toNodeId] ?: targetNode?.transport

        if (targetNode == null && targetTransport == null) {
            return false
        }

        val estimatedBytes = estimatePayloadBytes(message)
        totalBytesTransferred.addAndGet(estimatedBytes.toLong())
        nodes[fromNodeId]?.totalBytesTransferred?.addAndGet(estimatedBytes.toLong())
        totalPacketsDelivered.incrementAndGet()

        targetTransport?.deliverIncoming("endpoint-$fromNodeId", message)

        if (targetNode != null) {
            val responses = targetNode.receiveMessage(fromNodeId, message)
            for (resp in responses) {
                deliverMessage(toNodeId, fromNodeId, resp)
            }
        }

        return true
    }

    suspend fun broadcastMessage(senderNodeId: String, message: SyncMessage, excludeEndpoint: String? = null): Int {
        var delivered = 0
        val allTargets = (nodes.keys + transports.keys).distinct()
        for (targetId in allTargets) {
            if (targetId != senderNodeId && targetId != excludeEndpoint) {
                if (deliverMessage(senderNodeId, targetId, message)) {
                    delivered++
                }
            }
        }
        return delivered
    }

    /**
     * Executes concurrent burst deliveries across multiple sender-target pairs.
     */
    suspend fun deliverBurst(deliveries: List<Triple<String, String, SyncMessage>>): List<Boolean> = coroutineScope {
        val results = MutableList(deliveries.size) { false }
        val jobs = deliveries.mapIndexed { index, (sender, target, msg) ->
            launch {
                results[index] = deliverMessage(sender, target, msg)
            }
        }
        jobs.forEach { it.join() }
        results
    }

    suspend fun executeSyncRound(schedulerType: String = "VOI", byteBudgetPerWindow: Int = 1000) {
        val nodeKeys = nodes.keys.toList()

        for (i in nodeKeys.indices) {
            for (j in (i + 1) until nodeKeys.size) {
                val nodeA = nodeKeys[i]
                val nodeB = nodeKeys[j]

                if (canCommunicate(nodeA, nodeB)) {
                    val digestA = nodes[nodeA]!!.generateStateDigest()
                    val digestB = nodes[nodeB]!!.generateStateDigest()

                    exchangeWithBudget(nodeA, nodeB, digestB, schedulerType, byteBudgetPerWindow)
                    exchangeWithBudget(nodeB, nodeA, digestA, schedulerType, byteBudgetPerWindow)
                }
            }
        }
    }

    private suspend fun exchangeWithBudget(
        senderNodeId: String,
        receiverNodeId: String,
        receiverDigest: SyncMessage.StateDigest,
        schedulerType: String,
        byteBudget: Int
    ) {
        val sender = nodes[senderNodeId] ?: return
        if (nodes[receiverNodeId] == null) return

        val ourDigests = sender.observationDao.getAllSequenceDigests()
        val ourSeqMap = ourDigests.associate { it.authorDeviceId to it.maxSeq }

        val missingEvents = mutableListOf<ObservationEntity>()
        for ((authorId, ourMax) in ourSeqMap) {
            val peerMax = receiverDigest.sequences[authorId] ?: 0L
            if (ourMax > peerMax) {
                missingEvents.addAll(sender.observationDao.getAfterSequence(authorId, peerMax))
            }
        }

        if (missingEvents.isEmpty()) return

        val orderedEvents = when (schedulerType.uppercase()) {
            "VOI" -> missingEvents.sortedByDescending { sender.scheduler.scoreObservation(it) }
            "FIFO" -> missingEvents.sortedBy { it.observedAtDevice }
            "RANDOM" -> missingEvents.shuffled()
            else -> missingEvents
        }

        val eventsToSend = mutableListOf<ObservationEntity>()
        var accumulatedBytes = 0

        for (obs in orderedEvents) {
            val bytes = (obs.title.length + obs.description.length + 200) * 2
            if (accumulatedBytes + bytes <= byteBudget) {
                eventsToSend.add(obs)
                accumulatedBytes += bytes
            } else if (eventsToSend.isEmpty()) {
                eventsToSend.add(obs)
                break
            }
        }

        if (eventsToSend.isNotEmpty()) {
            val syncList = eventsToSend.map {
                SyncObservation(
                    eventId = it.eventId,
                    missionId = it.missionId,
                    authorDeviceId = it.authorDeviceId,
                    authorSequence = it.authorSequence,
                    eventType = it.eventType,
                    title = it.title,
                    description = it.description,
                    reportedStatus = it.reportedStatus,
                    verificationStatus = it.verificationStatus,
                    mapEdgeId = it.mapEdgeId,
                    observedAtDevice = it.observedAtDevice
                )
            }

            val batch = SyncMessage.EventBatch(
                senderDeviceId = senderNodeId,
                events = syncList
            )
            deliverMessage(senderNodeId, receiverNodeId, batch)
        }
    }

    suspend fun getHazardPropagationRounds(
        hazardEventId: String,
        schedulerType: String = "VOI",
        byteBudgetPerWindow: Int = 1000,
        maxRounds: Int = 20
    ): Int {
        for (round in 1..maxRounds) {
            executeSyncRound(schedulerType, byteBudgetPerWindow)

            var allHaveHazard = true
            for ((_, node) in nodes) {
                if (!node.observationDao.exists(hazardEventId)) {
                    allHaveHazard = false
                    break
                }
            }

            if (allHaveHazard) {
                return round
            }
        }
        return maxRounds
    }

    suspend fun runUntilMapConvergence(
        edgeId: String,
        expectedStatus: String,
        schedulerType: String = "VOI",
        byteBudgetPerWindow: Int = 1000,
        maxRounds: Int = 20
    ): Int {
        for (round in 1..maxRounds) {
            executeSyncRound(schedulerType, byteBudgetPerWindow)

            var allConverged = true
            for ((_, node) in nodes) {
                val edge = node.mapDao.getEdge(edgeId)
                if (edge == null || edge.routeStatus != expectedStatus) {
                    allConverged = false
                    break
                }
            }

            if (allConverged) {
                return round
            }
        }
        return maxRounds
    }

    fun estimatePayloadBytes(message: SyncMessage): Int {
        return when (message) {
            is SyncMessage.Hello -> 120
            is SyncMessage.StateDigest -> 80 + (message.sequences.size * 32)
            is SyncMessage.EventRequest -> 80 + (message.missingFromAuthors.size * 32)
            is SyncMessage.EventAck -> 80 + (message.acknowledgedEventIds.size * 36)
            is SyncMessage.EventBatch -> {
                var size = 100
                for (e in message.events) {
                    size += (e.title.length + e.description.length + 200) * 2
                }
                size
            }
            else -> 128
        }
    }
}
