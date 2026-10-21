package com.rescuemesh.ai.data.dao

import androidx.room.*
import com.rescuemesh.ai.data.entity.DeliveryReceiptEntity
import com.rescuemesh.ai.data.entity.OutboxEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {

    @Query("SELECT * FROM outbox WHERE status = 'PENDING' ORDER BY priority DESC, createdAt ASC")
    suspend fun getPendingByPriority(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPendingByFifo(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE status = 'PENDING' AND targetPeerId = :peerId ORDER BY priority DESC")
    suspend fun getPendingForPeer(peerId: String): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<OutboxEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(outbox: OutboxEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<OutboxEntity>)

    @Query("UPDATE outbox SET status = :status, lastAttemptAt = :attemptAt, retryCount = retryCount + 1 WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, attemptAt: Long = System.currentTimeMillis())

    @Query("UPDATE outbox SET status = :status, lastAttemptAt = :attemptAt WHERE eventId = :eventId")
    suspend fun updateStatusByEvent(eventId: String, status: String, attemptAt: Long = System.currentTimeMillis())

    @Query("UPDATE outbox SET priority = :priority, schedulerType = :schedulerType WHERE id = :id")
    suspend fun updatePriority(id: Long, priority: Float, schedulerType: String)

    @Query("DELETE FROM outbox WHERE status = 'DELIVERED'")
    suspend fun clearDelivered()

    @Query("UPDATE outbox SET status = 'PENDING' WHERE status = 'SENDING'")
    suspend fun resetOrphanedSendingToPending(): Int

    @Query("UPDATE outbox SET status = :status, retryCount = :retryCount, nextAttemptAt = :nextAttemptAt, lastAttemptAt = :attemptAt WHERE id = :id")
    suspend fun updateRetry(id: Long, status: String, retryCount: Int, nextAttemptAt: Long, attemptAt: Long = System.currentTimeMillis())

    /**
     * Check if we already have an outbox entry for this event+peer combination.
     * Prevents duplicate outbox entries.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM outbox WHERE eventId = :eventId AND (targetPeerId = :peerId OR (targetPeerId IS NULL AND :peerId IS NULL)) AND status != 'DELIVERED')")
    suspend fun hasPendingEntry(eventId: String, peerId: String?): Boolean

    @Query("SELECT COUNT(*) FROM outbox WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    // --- Delivery Receipts ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceipt(receipt: DeliveryReceiptEntity)

    @Query("SELECT * FROM delivery_receipts WHERE eventId = :eventId")
    suspend fun getReceipts(eventId: String): List<DeliveryReceiptEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM delivery_receipts WHERE eventId = :eventId AND peerDeviceId = :peerId)")
    suspend fun isDeliveredTo(eventId: String, peerId: String): Boolean
}
