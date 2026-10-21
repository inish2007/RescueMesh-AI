package com.rescuemesh.ai.data.repository

import com.rescuemesh.ai.data.dao.MetricsDao
import com.rescuemesh.ai.data.entity.MetricsEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Repository for recording and querying system metrics.
 * Every scheduler decision, transfer, and connection event is logged here
 * for research evaluation and comparison.
 */
class SyncRepository(
    private val metricsDao: MetricsDao
) {
    suspend fun recordSend(eventId: String, peerId: String, bytes: Int, schedulerType: String, priority: Float) {
        metricsDao.insert(
            MetricsEventEntity(
                metricType = "SEND",
                eventId = eventId,
                peerId = peerId,
                bytesTransferred = bytes,
                schedulerType = schedulerType,
                priorityScore = priority
            )
        )
    }

    suspend fun recordReceive(eventId: String, peerId: String, bytes: Int) {
        metricsDao.insert(
            MetricsEventEntity(
                metricType = "RECEIVE",
                eventId = eventId,
                peerId = peerId,
                bytesTransferred = bytes
            )
        )
    }

    suspend fun recordDelivery(eventId: String, peerId: String, latencyMs: Long) {
        metricsDao.insert(
            MetricsEventEntity(
                metricType = "DELIVERY",
                eventId = eventId,
                peerId = peerId,
                latencyMs = latencyMs
            )
        )
    }

    suspend fun recordQueueDecision(
        eventId: String,
        schedulerType: String,
        priority: Float,
        breakdown: Map<String, Float>
    ) {
        metricsDao.insert(
            MetricsEventEntity(
                metricType = "QUEUE_DECISION",
                eventId = eventId,
                schedulerType = schedulerType,
                priorityScore = priority,
                details = Json.encodeToString(breakdown)
            )
        )
    }

    suspend fun recordConnection(peerId: String) {
        metricsDao.insert(
            MetricsEventEntity(metricType = "CONNECTION", peerId = peerId)
        )
    }

    suspend fun recordDisconnection(peerId: String) {
        metricsDao.insert(
            MetricsEventEntity(metricType = "DISCONNECT", peerId = peerId)
        )
    }

    suspend fun recordBytesSent(peerId: String?, bytes: Int, eventId: String? = null) {
        metricsDao.insert(
            MetricsEventEntity(
                metricType = "BYTES_SENT",
                peerId = peerId,
                eventId = eventId,
                bytesTransferred = bytes
            )
        )
    }

    suspend fun recordBytesReceived(peerId: String?, bytes: Int, eventId: String? = null) {
        metricsDao.insert(
            MetricsEventEntity(
                metricType = "BYTES_RECEIVED",
                peerId = peerId,
                eventId = eventId,
                bytesTransferred = bytes
            )
        )
    }

    suspend fun recordPeersConnected(count: Int, peerId: String? = null) {
        metricsDao.insert(
            MetricsEventEntity(
                metricType = "PEERS_CONNECTED",
                peerId = peerId,
                bytesTransferred = count,
                details = "Connected peers count: $count"
            )
        )
    }

    suspend fun recordOutboxQueueDepth(depth: Int) {
        metricsDao.insert(
            MetricsEventEntity(
                metricType = "OUTBOX_QUEUE_DEPTH",
                bytesTransferred = depth,
                details = "Pending outbox queue depth: $depth"
            )
        )
    }

    suspend fun recordSync(peerId: String, details: String) {
        metricsDao.insert(
            MetricsEventEntity(
                metricType = "SYNC",
                peerId = peerId,
                details = details
            )
        )
    }

    fun observeRecentMetrics(limit: Int = 100): Flow<List<MetricsEventEntity>> =
        metricsDao.observeRecent(limit)

    fun observeTotalBytesSent(): Flow<Long?> = metricsDao.observeTotalBytesSent()
    fun observeTotalBytesReceived(): Flow<Long?> = metricsDao.observeTotalBytesReceived()
    fun observeLatestPeersConnected(): Flow<MetricsEventEntity?> = metricsDao.observeLatestPeersConnected()
    fun observePeersConnectedCount(): Flow<Int> = metricsDao.observePeersConnectedCount()
    fun observeLatestOutboxQueueDepth(): Flow<MetricsEventEntity?> = metricsDao.observeLatestOutboxQueueDepth()
    fun observeSyncEventCount(): Flow<Int> = metricsDao.observeSyncEventCount()
    fun observeSyncEvents(): Flow<List<MetricsEventEntity>> = metricsDao.observeSyncEvents()
    fun observeAvgDeliveryLatency(): Flow<Double?> = metricsDao.observeAvgDeliveryLatency()
    fun observeQueueDecisionCount(): Flow<Int> = metricsDao.observeQueueDecisionCount()

    suspend fun getAllMetrics(): List<MetricsEventEntity> = metricsDao.getAll()
}
