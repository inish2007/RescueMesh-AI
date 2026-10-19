package com.rescuemesh.ai.data.dao

import androidx.room.*
import com.rescuemesh.ai.data.entity.PeerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {

    @Query("SELECT * FROM peers ORDER BY lastSeenAt DESC")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE isConnected = 1")
    fun observeConnected(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE isConnected = 1")
    suspend fun getConnected(): List<PeerEntity>

    @Query("SELECT * FROM peers WHERE deviceId = :deviceId")
    suspend fun getById(deviceId: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE endpointId = :endpointId")
    suspend fun getByEndpointId(endpointId: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: PeerEntity)

    @Query("UPDATE peers SET isConnected = 0 WHERE endpointId = :endpointId")
    suspend fun markDisconnected(endpointId: String)

    @Query("UPDATE peers SET isConnected = 0")
    suspend fun markAllDisconnected()

    @Query("""
        UPDATE peers 
        SET lastSyncAt = :syncAt, 
            latestKnownSequences = :sequences 
        WHERE deviceId = :deviceId
    """)
    suspend fun updateSyncState(deviceId: String, syncAt: Long, sequences: String)

    @Query("""
        UPDATE peers 
        SET bytesSent = bytesSent + :bytes, 
            messagesSent = messagesSent + :count 
        WHERE deviceId = :deviceId
    """)
    suspend fun addSentStats(deviceId: String, bytes: Long, count: Int)

    @Query("""
        UPDATE peers 
        SET bytesReceived = bytesReceived + :bytes, 
            messagesReceived = messagesReceived + :count 
        WHERE deviceId = :deviceId
    """)
    suspend fun addReceivedStats(deviceId: String, bytes: Long, count: Int)

    @Query("SELECT COUNT(*) FROM peers WHERE isConnected = 1")
    fun observeConnectedCount(): Flow<Int>
}
