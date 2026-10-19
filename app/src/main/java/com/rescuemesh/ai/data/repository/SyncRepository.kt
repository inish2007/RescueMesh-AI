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

    fun observeRecentMetrics(limit: Int = 100): Flow<List<MetricsEventEntity>> =
        metricsDao.observeRecent(limit)

    fun observeTotalBytesSent(): Flow<Long?> = metricsDao.observeTotalBytesSent()
    fun observeTotalBytesReceived(): Flow<Long?> = metricsDao.observeTotalBytesReceived()
    fun observeAvgDeliveryLatency(): Flow<Double?> = metricsDao.observeAvgDeliveryLatency()
    fun observeQueueDecisionCount(): Flow<Int> = metricsDao.observeQueueDecisionCount()

    suspend fun getAllMetrics(): List<MetricsEventEntity> = metricsDao.getAll()
}
