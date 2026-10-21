package com.rescuemesh.ai.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rescuemesh.ai.data.entity.MissionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Mission lifecycle and state operations.
 * Scopes multi-responder operations and active mission context.
 */
@Dao
interface MissionDao {

    @Query("SELECT * FROM missions WHERE isActive = 1 LIMIT 1")
    fun observeActiveMission(): Flow<MissionEntity?>

    @Query("SELECT * FROM missions WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveMission(): MissionEntity?

    @Query("SELECT * FROM missions ORDER BY createdAt DESC")
    suspend fun getAllMissions(): List<MissionEntity>

    @Query("SELECT * FROM missions ORDER BY createdAt DESC")
    fun observeAllMissions(): Flow<List<MissionEntity>>

    @Query("SELECT * FROM missions WHERE missionId = :missionId LIMIT 1")
    suspend fun getMissionById(missionId: String): MissionEntity?

    @Query("SELECT * FROM missions WHERE pairingCode = :code COLLATE NOCASE LIMIT 1")
    suspend fun getMissionByPairingCode(code: String): MissionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMission(mission: MissionEntity)

    @Query("UPDATE missions SET isActive = CASE WHEN missionId = :missionId THEN 1 ELSE 0 END")
    suspend fun setActiveMission(missionId: String)
}
