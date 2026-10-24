package com.rescuemesh.ai.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rescuemesh.ai.data.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room Data Access Object for emergency broadcast chat and SOS-linked incident threads.
 */
@Dao
interface ChatDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(message: ChatMessageEntity): Long

    @Query("SELECT * FROM chat_messages WHERE missionId = :missionId ORDER BY timestamp ASC")
    fun getMessagesFlow(missionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE missionId = :missionId AND linkedEventId = :eventId ORDER BY timestamp ASC")
    fun getLinkedMessagesFlow(missionId: String, eventId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessage(messageId: String): ChatMessageEntity?

    @Query("SELECT * FROM chat_messages WHERE missionId = :missionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(missionId: String, limit: Int = 100): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE missionId = :missionId ORDER BY timestamp ASC")
    suspend fun getAllMessages(missionId: String): List<ChatMessageEntity>

    @Query("UPDATE chat_messages SET status = :status WHERE messageId = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

    @Query("UPDATE chat_messages SET ackPeerCount = :ackPeerCount, status = :status WHERE messageId = :messageId")
    suspend fun updateAckCount(messageId: String, ackPeerCount: Int, status: String = "SENT_TO_PEERS")

    @Query("UPDATE chat_messages SET ackPeerCount = ackPeerCount + 1, status = 'SENT_TO_PEERS' WHERE messageId = :messageId")
    suspend fun recordPeerAck(messageId: String)

    @Query("SELECT COUNT(*) FROM chat_messages WHERE messageId = :messageId")
    suspend fun exists(messageId: String): Int

    @Query("SELECT COUNT(*) FROM chat_messages WHERE missionId = :missionId")
    suspend fun count(missionId: String): Int

    @Query("SELECT MAX(authorSequence) FROM chat_messages WHERE senderId = :senderId")
    suspend fun getLatestSequence(senderId: String): Long?

    @Query("SELECT * FROM chat_messages WHERE senderId = :senderId AND authorSequence > :afterSequence ORDER BY authorSequence ASC")
    suspend fun getMessagesAfterSequence(senderId: String, afterSequence: Long): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE missionId = :missionId AND id NOT IN (SELECT id FROM chat_messages WHERE missionId = :missionId ORDER BY timestamp DESC LIMIT :keepCount)")
    suspend fun pruneOldMessages(missionId: String, keepCount: Int = 1000): Int
}
