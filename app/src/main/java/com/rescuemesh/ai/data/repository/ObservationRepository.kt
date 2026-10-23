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
    private val missionIdProvider: () -> String
) {
    // Secondary constructor for backward compatibility
    constructor(
        observationDao: ObservationDao,
        outboxDao: OutboxDao,
        deviceId: String,
        missionId: String
    ) : this(observationDao, outboxDao, deviceId, { missionId })

    val currentMissionId: String
        get() = missionIdProvider()

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
        evidencePath: String? = null,
        evidenceHash: String? = null
    ): ObservationEntity {
        val seq = localSequence.incrementAndGet()
        val now = System.currentTimeMillis()

        var validEvidencePath = evidencePath
        var validEvidenceHash = evidenceHash
        if (validEvidencePath != null) {
            val file = java.io.File(validEvidencePath)
            if (file.exists() && file.isFile && file.length() > 0L) {
                // Physical file exists and is non-empty
            } else {
                if (file.exists() && file.isFile) {
                    file.delete()
                }
                validEvidencePath = null
                validEvidenceHash = null
            }
        } else {
            validEvidenceHash = null
        }

        val observation = ObservationEntity(
            eventId = UUID.randomUUID().toString(),
            missionId = currentMissionId,
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
            evidencePath = validEvidencePath,
            evidenceHash = validEvidenceHash,
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
     * Returns true if this was a new observation, false if duplicate or foreign mission.
     */
    suspend fun receiveObservation(observation: ObservationEntity): Boolean {
        if (observation.missionId != currentMissionId) return false
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
        observationDao.observeAll(currentMissionId)

    fun observeCount(): Flow<Int> =
        observationDao.observeCount(currentMissionId)

    fun observeLocalCount(): Flow<Int> =
        observationDao.observeLocalCount(currentMissionId)

    fun observeReceivedCount(): Flow<Int> =
        observationDao.observeReceivedCount(currentMissionId)

    suspend fun getAll(): List<ObservationEntity> =
        observationDao.getAll(currentMissionId)

    /**
     * Attaches verified evidence to an existing observation after SHA-256 validation.
     * Updates local database state with the absolute file path and verification status.
     * If file does not exist, is empty, or hash does not match, sets status to CORRUPTED.
     */
    suspend fun attachVerifiedEvidence(eventId: String, file: java.io.File, expectedHash: String): Boolean {
        if (!file.exists() || !file.isFile || file.length() == 0L) {
            if (file.exists() && file.isFile) file.delete()
            observationDao.updateEvidence(eventId, null, "CORRUPTED")
            return false
        }

        val computedHash = computeSha256(file)
        return if (computedHash.equals(expectedHash, ignoreCase = true)) {
            observationDao.updateEvidence(eventId, file.absolutePath, "VERIFIED")
            true
        } else {
            file.delete()
            observationDao.updateEvidence(eventId, null, "CORRUPTED")
            false
        }
    }

    private fun computeSha256(file: java.io.File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        java.io.FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
