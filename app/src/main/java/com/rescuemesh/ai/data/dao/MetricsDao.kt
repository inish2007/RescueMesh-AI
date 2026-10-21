package com.rescuemesh.ai.data.dao

import androidx.room.*
import com.rescuemesh.ai.data.entity.MetricsEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MetricsDao {

    @Insert
    suspend fun insert(event: MetricsEventEntity)

    @Insert
    suspend fun insertAll(events: List<MetricsEventEntity>)

    @Query("SELECT * FROM metrics_events ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<MetricsEventEntity>>

    @Query("SELECT * FROM metrics_events ORDER BY timestamp ASC")
    suspend fun getAll(): List<MetricsEventEntity>

    @Query("SELECT * FROM metrics_events WHERE metricType = :type ORDER BY timestamp DESC")
    suspend fun getByType(type: String): List<MetricsEventEntity>

    @Query("SELECT SUM(bytesTransferred) FROM metrics_events WHERE metricType = 'SEND' OR metricType = 'BYTES_SENT'")
    fun observeTotalBytesSent(): Flow<Long?>

    @Query("SELECT SUM(bytesTransferred) FROM metrics_events WHERE metricType = 'RECEIVE' OR metricType = 'BYTES_RECEIVED'")
    fun observeTotalBytesReceived(): Flow<Long?>

    @Query("SELECT * FROM metrics_events WHERE metricType = 'PEERS_CONNECTED' ORDER BY timestamp DESC LIMIT 1")
    fun observeLatestPeersConnected(): Flow<MetricsEventEntity?>

    @Query("SELECT COUNT(*) FROM metrics_events WHERE metricType = 'PEERS_CONNECTED' OR metricType = 'CONNECTION'")
    fun observePeersConnectedCount(): Flow<Int>

    @Query("SELECT * FROM metrics_events WHERE metricType = 'OUTBOX_QUEUE_DEPTH' ORDER BY timestamp DESC LIMIT 1")
    fun observeLatestOutboxQueueDepth(): Flow<MetricsEventEntity?>

    @Query("SELECT COUNT(*) FROM metrics_events WHERE metricType = 'SYNC' OR metricType = 'STATE_DIGEST' OR metricType = 'RECONCILIATION'")
    fun observeSyncEventCount(): Flow<Int>

    @Query("SELECT * FROM metrics_events WHERE metricType = 'SYNC' OR metricType = 'STATE_DIGEST' OR metricType = 'RECONCILIATION' ORDER BY timestamp DESC")
    fun observeSyncEvents(): Flow<List<MetricsEventEntity>>

    @Query("SELECT AVG(latencyMs) FROM metrics_events WHERE metricType = 'DELIVERY' AND latencyMs IS NOT NULL")
    fun observeAvgDeliveryLatency(): Flow<Double?>

    @Query("SELECT COUNT(*) FROM metrics_events WHERE metricType = 'QUEUE_DECISION'")
    fun observeQueueDecisionCount(): Flow<Int>

    @Query("DELETE FROM metrics_events")
    suspend fun clearAll()
}
