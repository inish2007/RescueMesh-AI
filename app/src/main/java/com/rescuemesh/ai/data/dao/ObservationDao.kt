package com.rescuemesh.ai.data.dao

import androidx.room.*
import com.rescuemesh.ai.data.entity.ObservationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ObservationDao {

    @Query("SELECT * FROM observations WHERE missionId = :missionId ORDER BY observedAtDevice DESC")
    fun observeAll(missionId: String): Flow<List<ObservationEntity>>

    @Query("SELECT * FROM observations WHERE missionId = :missionId ORDER BY observedAtDevice DESC")
    suspend fun getAll(missionId: String): List<ObservationEntity>

    @Query("SELECT * FROM observations WHERE eventId = :eventId")
    suspend fun getById(eventId: String): ObservationEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM observations WHERE eventId = :eventId)")
    suspend fun exists(eventId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(observation: ObservationEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(observations: List<ObservationEntity>)

    @Query("SELECT COUNT(*) FROM observations WHERE missionId = :missionId")
    fun observeCount(missionId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM observations WHERE missionId = :missionId AND isLocal = 1")
    fun observeLocalCount(missionId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM observations WHERE missionId = :missionId AND isLocal = 0")
    fun observeReceivedCount(missionId: String): Flow<Int>

    /**
     * Get the latest author sequence for a specific device.
     * Used for digest-based sync: tells peers what we already have from each author.
     */
    @Query("SELECT COALESCE(MAX(authorSequence), 0) FROM observations WHERE authorDeviceId = :deviceId")
    suspend fun getLatestSequence(deviceId: String): Long

    /**
     * Get all known author device IDs and their latest sequences.
     * Returns pairs of (authorDeviceId, maxSequence).
     */
    @Query("SELECT authorDeviceId, MAX(authorSequence) as maxSeq FROM observations GROUP BY authorDeviceId")
    suspend fun getAllSequenceDigests(): List<SequenceDigest>

    /**
     * Get observations from a specific author that are newer than a given sequence number.
     * Used to send missing events to a peer.
     */
    @Query("SELECT * FROM observations WHERE authorDeviceId = :authorDeviceId AND authorSequence > :afterSequence ORDER BY authorSequence ASC")
    suspend fun getAfterSequence(authorDeviceId: String, afterSequence: Long): List<ObservationEntity>

    @Query("SELECT * FROM observations WHERE mapEdgeId = :edgeId ORDER BY observedAtDevice DESC")
    suspend fun getByEdgeId(edgeId: String): List<ObservationEntity>
}

data class SequenceDigest(
    val authorDeviceId: String,
    val maxSeq: Long
)
