package com.rescuemesh.ai.data.repository

import com.rescuemesh.ai.data.dao.ObservationDao
import com.rescuemesh.ai.data.dao.OutboxDao
import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.data.entity.OutboxEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Repository for creating and querying observations.
 * Ensures every observation is persisted locally before any sync attempt.
 */
class ObservationRepository(
    private val observationDao: ObservationDao,
    private val outboxDao: OutboxDao,
    private val deviceId: String,
    private val missionId: String
) {
    private val localSequence = AtomicLong(0)

    /** Initialize the sequence counter from the database */
    suspend fun initSequence() {
        val latest = observationDao.getLatestSequence(deviceId)
        localSequence.set(latest)
    }

    /**
     * Create a new observation from local user input.
     * The observation is immediately saved to the local database
     * and an outbox entry is created for each connected peer.
     */
    suspend fun createObservation(
        eventType: String,
        title: String,
        description: String = "",
        reportedStatus: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        accuracy: Float? = null,
        mapEdgeId: String? = null,
        mapNodeId: String? = null,
        evidencePath: String? = null
    ): ObservationEntity {
        val seq = localSequence.incrementAndGet()
        val now = System.currentTimeMillis()

        val observation = ObservationEntity(
            eventId = UUID.randomUUID().toString(),
            missionId = missionId,
            authorDeviceId = deviceId,
            authorSequence = seq,
            eventType = eventType,
            title = title,
            description = description,
            reportedStatus = reportedStatus,
            sourceType = "HUMAN_OBSERVATION",
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            mapEdgeId = mapEdgeId,
            mapNodeId = mapNodeId,
            evidencePath = evidencePath,
            verificationStatus = "UNVERIFIED",
            observedAtDevice = now,
            receivedAt = now,
            isLocal = true,
            relayHopsRemaining = 3
        )

        // Step 1: Persist locally BEFORE any network attempt
        observationDao.insert(observation)

        // Step 2: Create a broadcast outbox entry (null targetPeerId = all peers)
        outboxDao.insert(
            OutboxEntity(
                eventId = observation.eventId,
                targetPeerId = null,
                priority = 0f,  // Will be scored by PriorityScheduler
                createdAt = now,
                status = "PENDING"
            )
        )

        return observation
    }

    /**
     * Store an observation received from a peer.
     * Returns true if this was a new observation, false if duplicate.
     */
    suspend fun receiveObservation(observation: ObservationEntity): Boolean {
        if (observationDao.exists(observation.eventId)) return false

        observationDao.insert(
            observation.copy(
                isLocal = false,
                receivedAt = System.currentTimeMillis()
            )
        )
        return true
    }

    fun observeAll(): Flow<List<ObservationEntity>> =
        observationDao.observeAll(missionId)

    fun observeCount(): Flow<Int> =
        observationDao.observeCount(missionId)

    fun observeLocalCount(): Flow<Int> =
        observationDao.observeLocalCount(missionId)

    fun observeReceivedCount(): Flow<Int> =
        observationDao.observeReceivedCount(missionId)

    suspend fun getAll(): List<ObservationEntity> =
        observationDao.getAll(missionId)
}
