package com.rescuemesh.ai.fakes

import com.rescuemesh.ai.data.dao.MetricsDao
import com.rescuemesh.ai.data.entity.MetricsEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory thread-safe fake DAO for system and benchmark metrics collection.
 * Backed by [ConcurrentHashMap] and [AtomicLong] to support concurrent reporting
 * from simulated rescue nodes during multi-round benchmark executions.
 */
class FakeMetricsDao : MetricsDao {
    private val events = ConcurrentHashMap<Long, MetricsEventEntity>()
    private val nextId = AtomicLong(1L)

    override suspend fun insert(event: MetricsEventEntity) {
        val id = if (event.id == 0L) {
            nextId.getAndIncrement()
        } else {
            nextId.updateAndGet { current -> maxOf(current, event.id + 1) }
            event.id
        }
        events[id] = event.copy(id = id)
    }

    override suspend fun insertAll(events: List<MetricsEventEntity>) {
        for (event in events) {
            insert(event)
        }
    }

    override fun observeRecent(limit: Int): Flow<List<MetricsEventEntity>> {
        return flowOf(events.values.sortedByDescending { it.timestamp }.take(limit))
    }

    override suspend fun getAll(): List<MetricsEventEntity> {
        return events.values.sortedBy { it.timestamp }
    }

    override suspend fun getByType(type: String): List<MetricsEventEntity> {
        return events.values
            .filter { it.metricType == type }
            .sortedByDescending { it.timestamp }
    }

    override fun observeTotalBytesSent(): Flow<Long?> {
        val sum = events.values
            .filter { it.metricType == "SEND" || it.metricType == "BYTES_SENT" }
            .mapNotNull { it.bytesTransferred?.toLong() }
            .takeIf { it.isNotEmpty() }
            ?.sum()
        return flowOf(sum)
    }

    override fun observeTotalBytesReceived(): Flow<Long?> {
        val sum = events.values
            .filter { it.metricType == "RECEIVE" || it.metricType == "BYTES_RECEIVED" }
            .mapNotNull { it.bytesTransferred?.toLong() }
            .takeIf { it.isNotEmpty() }
            ?.sum()
        return flowOf(sum)
    }

    override fun observeLatestPeersConnected(): Flow<MetricsEventEntity?> {
        val latest = events.values
            .filter { it.metricType == "PEERS_CONNECTED" }
            .maxByOrNull { it.timestamp }
        return flowOf(latest)
    }

    override fun observePeersConnectedCount(): Flow<Int> {
        val count = events.values.count { it.metricType == "PEERS_CONNECTED" || it.metricType == "CONNECTION" }
        return flowOf(count)
    }

    override fun observeLatestOutboxQueueDepth(): Flow<MetricsEventEntity?> {
        val latest = events.values
            .filter { it.metricType == "OUTBOX_QUEUE_DEPTH" }
            .maxByOrNull { it.timestamp }
        return flowOf(latest)
    }

    override fun observeSyncEventCount(): Flow<Int> {
        val count = events.values.count {
            it.metricType == "SYNC" || it.metricType == "STATE_DIGEST" || it.metricType == "RECONCILIATION"
        }
        return flowOf(count)
    }

    override fun observeSyncEvents(): Flow<List<MetricsEventEntity>> {
        val list = events.values
            .filter { it.metricType == "SYNC" || it.metricType == "STATE_DIGEST" || it.metricType == "RECONCILIATION" }
            .sortedByDescending { it.timestamp }
        return flowOf(list)
    }

    override fun observeAvgDeliveryLatency(): Flow<Double?> {
        val latencies = events.values
            .filter { it.metricType == "DELIVERY" && it.latencyMs != null }
            .mapNotNull { it.latencyMs?.toDouble() }
        val avg = if (latencies.isNotEmpty()) latencies.average() else null
        return flowOf(avg)
    }

    override fun observeQueueDecisionCount(): Flow<Int> {
        return flowOf(events.values.count { it.metricType == "QUEUE_DECISION" })
    }

    override suspend fun clearAll() {
        events.clear()
        nextId.set(1L)
    }
}
