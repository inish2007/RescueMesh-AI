package com.rescuemesh.ai.fakes

import com.rescuemesh.ai.data.dao.MapDao
import com.rescuemesh.ai.data.dao.MissionDao
import com.rescuemesh.ai.data.dao.ObservationDao
import com.rescuemesh.ai.data.dao.OutboxDao
import com.rescuemesh.ai.data.dao.SequenceDigest
import com.rescuemesh.ai.data.entity.DeliveryReceiptEntity
import com.rescuemesh.ai.data.entity.MapEdgeEntity
import com.rescuemesh.ai.data.entity.MapNodeEntity
import com.rescuemesh.ai.data.entity.MissionEntity
import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.data.entity.OutboxEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure in-memory thread-safe fake DAOs for multi-node simulation without Android SQLite dependencies.
 * All collections are backed by [ConcurrentHashMap] and atomic primitives to support concurrent
 * execution across simulated nodes without [java.util.ConcurrentModificationException].
 */
class FakeObservationDao : ObservationDao {
    private val observations = ConcurrentHashMap<String, ObservationEntity>()

    override fun observeAll(missionId: String): Flow<List<ObservationEntity>> {
        return flowOf(observations.values.filter { it.missionId == missionId }.sortedByDescending { it.observedAtDevice })
    }

    override suspend fun getAll(missionId: String): List<ObservationEntity> {
        return observations.values.filter { it.missionId == missionId }.sortedByDescending { it.observedAtDevice }
    }

    override suspend fun getById(eventId: String): ObservationEntity? {
        return observations[eventId]
    }

    override suspend fun exists(eventId: String): Boolean {
        return observations.containsKey(eventId)
    }

    override suspend fun insert(observation: ObservationEntity) {
        observations.putIfAbsent(observation.eventId, observation)
    }

    override suspend fun insertAll(observations: List<ObservationEntity>) {
        for (obs in observations) {
            insert(obs)
        }
    }

    override fun observeCount(missionId: String): Flow<Int> {
        return flowOf(observations.values.count { it.missionId == missionId })
    }

    override fun observeLocalCount(missionId: String): Flow<Int> {
        return flowOf(observations.values.count { it.missionId == missionId && it.isLocal })
    }

    override fun observeReceivedCount(missionId: String): Flow<Int> {
        return flowOf(observations.values.count { it.missionId == missionId && !it.isLocal })
    }

    override suspend fun getLatestSequence(deviceId: String): Long {
        return observations.values
            .filter { it.authorDeviceId == deviceId }
            .maxOfOrNull { it.authorSequence } ?: 0L
    }

    override suspend fun getAllSequenceDigests(): List<SequenceDigest> {
        return observations.values
            .groupBy { it.authorDeviceId }
            .map { (deviceId, list) ->
                SequenceDigest(authorDeviceId = deviceId, maxSeq = list.maxOf { it.authorSequence })
            }
    }

    override suspend fun getAfterSequence(authorDeviceId: String, afterSequence: Long): List<ObservationEntity> {
        return observations.values
            .filter { it.authorDeviceId == authorDeviceId && it.authorSequence > afterSequence }
            .sortedBy { it.authorSequence }
    }

    override suspend fun getByEdgeId(edgeId: String): List<ObservationEntity> {
        return observations.values
            .filter { it.mapEdgeId == edgeId }
            .sortedByDescending { it.observedAtDevice }
    }

    fun clear() {
        observations.clear()
    }
}

class FakeMapDao : MapDao {
    private val nodes = ConcurrentHashMap<String, MapNodeEntity>()
    private val edges = ConcurrentHashMap<String, MapEdgeEntity>()

    override fun observeNodes(missionId: String): Flow<List<MapNodeEntity>> {
        return flowOf(nodes.values.filter { it.missionId == missionId })
    }

    override suspend fun getAllNodes(missionId: String): List<MapNodeEntity> {
        return nodes.values.filter { it.missionId == missionId }
    }

    override suspend fun getNode(nodeId: String): MapNodeEntity? {
        return nodes[nodeId]
    }

    override suspend fun insertNode(node: MapNodeEntity) {
        nodes[node.nodeId] = node
    }

    override suspend fun insertNodes(nodes: List<MapNodeEntity>) {
        for (node in nodes) {
            insertNode(node)
        }
    }

    override fun observeEdges(missionId: String): Flow<List<MapEdgeEntity>> {
        return flowOf(edges.values.filter { it.missionId == missionId })
    }

    override suspend fun getAllEdges(missionId: String): List<MapEdgeEntity> {
        return edges.values.filter { it.missionId == missionId }
    }

    override suspend fun getEdge(edgeId: String): MapEdgeEntity? {
        return edges[edgeId]
    }

    override suspend fun insertEdge(edge: MapEdgeEntity) {
        edges[edge.edgeId] = edge
    }

    override suspend fun insertEdges(edges: List<MapEdgeEntity>) {
        for (edge in edges) {
            insertEdge(edge)
        }
    }

    override suspend fun updateEdgeStatus(
        edgeId: String,
        status: String,
        observationId: String?,
        conflictIds: String?,
        updatedAt: Long
    ) {
        edges.computeIfPresent(edgeId) { _, existing ->
            existing.copy(
                routeStatus = status,
                lastObservationId = observationId,
                conflictingObservationIds = conflictIds,
                updatedAt = updatedAt
            )
        }
    }

    override suspend fun getEdgesForNode(nodeId: String): List<MapEdgeEntity> {
        return edges.values.filter { it.fromNodeId == nodeId || it.toNodeId == nodeId }
    }

    fun clear() {
        nodes.clear()
        edges.clear()
    }
}

class FakeOutboxDao : OutboxDao {
    private val outboxEntries = ConcurrentHashMap<Long, OutboxEntity>()
    private val receipts = ConcurrentHashMap<Pair<String, String>, DeliveryReceiptEntity>()
    private val nextId = AtomicLong(1L)

    override suspend fun getPendingByPriority(): List<OutboxEntity> {
        return outboxEntries.values
            .filter { it.status == "PENDING" }
            .sortedWith(compareByDescending<OutboxEntity> { it.priority }.thenBy { it.createdAt })
    }

    override suspend fun getPendingByFifo(): List<OutboxEntity> {
        return outboxEntries.values
            .filter { it.status == "PENDING" }
            .sortedBy { it.createdAt }
    }

    override suspend fun getPendingForPeer(peerId: String): List<OutboxEntity> {
        return outboxEntries.values
            .filter { it.status == "PENDING" && it.targetPeerId == peerId }
            .sortedByDescending { it.priority }
    }

    override fun observeRecent(limit: Int): Flow<List<OutboxEntity>> {
        return flowOf(outboxEntries.values.sortedByDescending { it.createdAt }.take(limit))
    }

    override suspend fun insert(outbox: OutboxEntity): Long {
        val id = if (outbox.id == 0L) {
            nextId.getAndIncrement()
        } else {
            nextId.updateAndGet { current -> maxOf(current, outbox.id + 1) }
            outbox.id
        }
        val entry = outbox.copy(id = id)
        outboxEntries[id] = entry
        return id
    }

    override suspend fun insertAll(entries: List<OutboxEntity>) {
        for (entry in entries) {
            insert(entry)
        }
    }

    override suspend fun updateStatus(id: Long, status: String, attemptAt: Long) {
        outboxEntries.computeIfPresent(id) { _, entry ->
            entry.copy(
                status = status,
                lastAttemptAt = attemptAt,
                retryCount = entry.retryCount + 1
            )
        }
    }

    override suspend fun updateStatusByEvent(eventId: String, status: String, attemptAt: Long) {
        val matchingIds = outboxEntries.values.filter { it.eventId == eventId }.map { it.id }
        for (id in matchingIds) {
            updateStatus(id, status, attemptAt)
        }
    }

    override suspend fun updatePriority(id: Long, priority: Float, schedulerType: String) {
        outboxEntries.computeIfPresent(id) { _, entry ->
            entry.copy(
                priority = priority,
                schedulerType = schedulerType
            )
        }
    }

    override suspend fun clearDelivered() {
        outboxEntries.values.removeIf { it.status == "DELIVERED" }
    }

    override suspend fun resetOrphanedSendingToPending(): Int {
        var count = 0
        for ((id, entry) in outboxEntries) {
            if (entry.status == "SENDING") {
                outboxEntries[id] = entry.copy(status = "PENDING")
                count++
            }
        }
        return count
    }

    override suspend fun updateRetry(id: Long, status: String, retryCount: Int, nextAttemptAt: Long, attemptAt: Long) {
        outboxEntries.computeIfPresent(id) { _, entry ->
            entry.copy(
                status = status,
                retryCount = retryCount,
                nextAttemptAt = nextAttemptAt,
                lastAttemptAt = attemptAt
            )
        }
    }

    override suspend fun hasPendingEntry(eventId: String, peerId: String?): Boolean {
        return outboxEntries.values.any {
            it.eventId == eventId &&
            (it.targetPeerId == peerId || (it.targetPeerId == null && peerId == null)) &&
            it.status != "DELIVERED"
        }
    }

    override fun observePendingCount(): Flow<Int> {
        return flowOf(outboxEntries.values.count { it.status == "PENDING" })
    }

    override suspend fun insertReceipt(receipt: DeliveryReceiptEntity) {
        receipts[Pair(receipt.eventId, receipt.peerDeviceId)] = receipt
    }

    override suspend fun getReceipts(eventId: String): List<DeliveryReceiptEntity> {
        return receipts.values.filter { it.eventId == eventId }
    }

    override suspend fun isDeliveredTo(eventId: String, peerId: String): Boolean {
        return receipts.containsKey(Pair(eventId, peerId))
    }

    fun clear() {
        outboxEntries.clear()
        receipts.clear()
        nextId.set(1L)
    }
}

class FakeMissionDao : MissionDao {
    private val missions = ConcurrentHashMap<String, MissionEntity>()
    private val activeFlow = MutableStateFlow<MissionEntity?>(null)
    private val allMissionsFlow = MutableStateFlow<List<MissionEntity>>(emptyList())
    private val lock = Any()

    private fun syncFlows() {
        activeFlow.value = missions.values.firstOrNull { it.isActive }
        allMissionsFlow.value = missions.values.sortedByDescending { it.createdAt }
    }

    override fun observeActiveMission(): Flow<MissionEntity?> {
        return activeFlow.asStateFlow()
    }

    override suspend fun getActiveMission(): MissionEntity? = synchronized(lock) {
        missions.values.firstOrNull { it.isActive }
    }

    override suspend fun getAllMissions(): List<MissionEntity> = synchronized(lock) {
        missions.values.sortedByDescending { it.createdAt }
    }

    override fun observeAllMissions(): Flow<List<MissionEntity>> {
        return allMissionsFlow.asStateFlow()
    }

    override suspend fun getMissionById(missionId: String): MissionEntity? = synchronized(lock) {
        missions[missionId]
    }

    override suspend fun getMissionByPairingCode(code: String): MissionEntity? = synchronized(lock) {
        missions.values.firstOrNull { it.pairingCode.equals(code.trim(), ignoreCase = true) }
    }

    override suspend fun insertMission(mission: MissionEntity) {
        synchronized(lock) {
            missions[mission.missionId] = mission
            syncFlows()
        }
    }

    override suspend fun setActiveMission(missionId: String) {
        synchronized(lock) {
            for ((id, mission) in missions) {
                missions[id] = mission.copy(isActive = (id == missionId))
            }
            syncFlows()
        }
    }

    fun clear() {
        synchronized(lock) {
            missions.clear()
            syncFlows()
        }
    }
}

