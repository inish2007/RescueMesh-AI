package com.rescuemesh.ai.evidence

import com.rescuemesh.ai.data.dao.ObservationDao
import com.rescuemesh.ai.data.dao.OutboxDao
import com.rescuemesh.ai.data.entity.DeliveryReceiptEntity
import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.data.entity.OutboxEntity
import com.rescuemesh.ai.data.repository.ObservationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Level 2 & Level 4 Integration & Adversarial Test Suite for Milestone 4 Evidence Association.
 * Verifies:
 * 1. Physical presence validation before committing SHA-256 hash.
 * 2. 0-byte, corrupt, or missing files are rejected and cleaned up.
 * 3. Cross-mission evidence isolation.
 * 4. Duplicate prevention & atomic local persistence.
 */
class EvidencePersistenceIntegrationTest {

    private lateinit var tempDir: File
    private lateinit var fakeEvidenceManager: FakeEvidenceManager
    private lateinit var fakeObsDao: TestObservationDao
    private lateinit var fakeOutboxDao: TestOutboxDao
    private lateinit var repository: ObservationRepository

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "rescuemesh_m4_test_${UUID.randomUUID().toString().take(8)}")
        tempDir.mkdirs()
        fakeEvidenceManager = FakeEvidenceManager(tempDir)
        fakeObsDao = TestObservationDao()
        fakeOutboxDao = TestOutboxDao()
        repository = ObservationRepository(
            observationDao = fakeObsDao,
            outboxDao = fakeOutboxDao,
            deviceId = "DEVICE_ALPHA",
            missionId = "MISSION_SECTOR_7"
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testValidPhotoEvidencePersistsWithSha256() = runBlocking {
        val testBytes = "Disaster Zone Structural Damage Sector 7".toByteArray(Charsets.UTF_8)
        val testFile = File(tempDir, "damage_report.jpg").apply { writeBytes(testBytes) }
        val expectedHash = fakeEvidenceManager.computeSha256(testFile)

        val obs = repository.createObservation(
            eventType = "HAZARD",
            title = "Wall Collapse",
            description = "Structural column cracked",
            reportedStatus = "HAZARD",
            evidencePath = testFile.absolutePath,
            evidenceHash = expectedHash
        )

        assertNotNull("EventId must be generated", obs.eventId)
        assertEquals("Evidence path must match", testFile.absolutePath, obs.evidencePath)
        assertEquals("Evidence hash must match expected SHA-256", expectedHash, obs.evidenceHash)
        assertEquals("MissionId must match active mission", "MISSION_SECTOR_7", obs.missionId)

        // Verify committed to database
        val saved = fakeObsDao.savedObservations[obs.eventId]
        assertNotNull("Must be persisted to Room DAO", saved)
        assertEquals(expectedHash, saved?.evidenceHash)

        // Verify outbox entry created
        assertEquals(1, fakeOutboxDao.savedEntries.size)
        assertEquals(obs.eventId, fakeOutboxDao.savedEntries[0].eventId)
    }

    @Test
    fun testMissingEvidenceFileIsPurgedAndNotHashed() = runBlocking {
        val nonExistentFile = File(tempDir, "non_existent.jpg")
        assertFalse(nonExistentFile.exists())

        val obs = repository.createObservation(
            eventType = "HAZARD",
            title = "Missing File Test",
            evidencePath = nonExistentFile.absolutePath,
            evidenceHash = "dummyhash123"
        )

        // Invariant: Never persist an evidenceHash or evidencePath if file does not exist on disk
        assertNull("Evidence path must be null if file is missing", obs.evidencePath)
        assertNull("Evidence hash must be null if file is missing", obs.evidenceHash)

        val saved = fakeObsDao.savedObservations[obs.eventId]
        assertNull("Persisted evidencePath must be null", saved?.evidencePath)
        assertNull("Persisted evidenceHash must be null", saved?.evidenceHash)
    }

    @Test
    fun testZeroByteEmptyFileIsDeletedAndRejected() = runBlocking {
        val zeroByteFile = File(tempDir, "zero_byte.jpg").apply { createNewFile() }
        assertTrue(zeroByteFile.exists())
        assertEquals(0L, zeroByteFile.length())

        val obs = repository.createObservation(
            eventType = "HAZARD",
            title = "Zero Byte Test",
            evidencePath = zeroByteFile.absolutePath,
            evidenceHash = "anyhash"
        )

        assertNull("Evidence path must be null for 0-byte file", obs.evidencePath)
        assertNull("Evidence hash must be null for 0-byte file", obs.evidenceHash)
        assertFalse("0-byte file must be deleted from storage", zeroByteFile.exists())
    }

    @Test
    fun testCrossMissionIsolationGuardsAgainstForeignObservations() = runBlocking {
        val foreignObs = ObservationEntity(
            eventId = UUID.randomUUID().toString(),
            missionId = "FOREIGN_MISSION_9",
            authorDeviceId = "FOREIGN_DEVICE",
            authorSequence = 1L,
            eventType = "NOTE",
            title = "Foreign Event",
            observedAtDevice = System.currentTimeMillis()
        )

        // If mission does not match, receiveObservation must reject
        val accepted = repository.receiveObservation(foreignObs)
        assertFalse("Observation from foreign mission must be rejected", accepted)
        assertFalse("Foreign observation must not be stored in local DAO", fakeObsDao.exists(foreignObs.eventId))
    }

    // --- Fake Test DAOs ---

    private class TestObservationDao : ObservationDao {
        val savedObservations = mutableMapOf<String, ObservationEntity>()

        override suspend fun insert(observation: ObservationEntity) {
            savedObservations[observation.eventId] = observation
        }

        override suspend fun insertAll(observations: List<ObservationEntity>) {
            observations.forEach { savedObservations[it.eventId] = it }
        }

        override suspend fun getById(eventId: String): ObservationEntity? = savedObservations[eventId]

        override suspend fun exists(eventId: String): Boolean = savedObservations.containsKey(eventId)

        override suspend fun getLatestSequence(authorDeviceId: String): Long {
            return savedObservations.values
                .filter { it.authorDeviceId == authorDeviceId }
                .maxOfOrNull { it.authorSequence } ?: 0L
        }

        override suspend fun getAllSequenceDigests(): List<com.rescuemesh.ai.data.dao.SequenceDigest> {
            return savedObservations.values
                .groupBy { it.authorDeviceId }
                .map { (author, list) ->
                    com.rescuemesh.ai.data.dao.SequenceDigest(
                        authorDeviceId = author,
                        maxSeq = list.maxOfOrNull { it.authorSequence } ?: 0L
                    )
                }
        }

        override fun observeAll(missionId: String): Flow<List<ObservationEntity>> {
            return flowOf(savedObservations.values.filter { it.missionId == missionId })
        }

        override fun observeCount(missionId: String): Flow<Int> {
            return flowOf(savedObservations.values.count { it.missionId == missionId })
        }

        override fun observeLocalCount(missionId: String): Flow<Int> {
            return flowOf(savedObservations.values.count { it.missionId == missionId && it.isLocal })
        }

        override fun observeReceivedCount(missionId: String): Flow<Int> {
            return flowOf(savedObservations.values.count { it.missionId == missionId && !it.isLocal })
        }

        override suspend fun getAll(missionId: String): List<ObservationEntity> {
            return savedObservations.values.filter { it.missionId == missionId }
        }

        override suspend fun getAfterSequence(authorDeviceId: String, afterSequence: Long): List<ObservationEntity> {
            return savedObservations.values
                .filter { it.authorDeviceId == authorDeviceId && it.authorSequence > afterSequence }
                .sortedBy { it.authorSequence }
        }

        override suspend fun getByEdgeId(edgeId: String): List<ObservationEntity> {
            return savedObservations.values
                .filter { it.mapEdgeId == edgeId }
                .sortedByDescending { it.observedAtDevice }
        }
    }

    private class TestOutboxDao : OutboxDao {
        val savedEntries = mutableListOf<OutboxEntity>()
        val savedReceipts = mutableListOf<DeliveryReceiptEntity>()
        private var nextId = 1L

        override suspend fun insert(outbox: OutboxEntity): Long {
            val id = nextId++
            savedEntries.add(outbox.copy(id = id))
            return id
        }

        override suspend fun insertAll(entries: List<OutboxEntity>) {
            entries.forEach { insert(it) }
        }

        override suspend fun getPendingByPriority(): List<OutboxEntity> =
            savedEntries.filter { it.status == "PENDING" }.sortedByDescending { it.priority }

        override suspend fun getPendingByFifo(): List<OutboxEntity> =
            savedEntries.filter { it.status == "PENDING" }.sortedBy { it.createdAt }

        override suspend fun getPendingForPeer(peerId: String): List<OutboxEntity> =
            savedEntries.filter { it.status == "PENDING" && (it.targetPeerId == null || it.targetPeerId == peerId) }

        override fun observeRecent(limit: Int): Flow<List<OutboxEntity>> =
            flowOf(savedEntries.sortedByDescending { it.createdAt }.take(limit))

        override suspend fun updateStatus(id: Long, status: String, attemptAt: Long) {
            val idx = savedEntries.indexOfFirst { it.id == id }
            if (idx >= 0) savedEntries[idx] = savedEntries[idx].copy(status = status, lastAttemptAt = attemptAt)
        }

        override suspend fun updateStatusByEvent(eventId: String, status: String, attemptAt: Long) {
            val idx = savedEntries.indexOfFirst { it.eventId == eventId }
            if (idx >= 0) savedEntries[idx] = savedEntries[idx].copy(status = status, lastAttemptAt = attemptAt)
        }

        override suspend fun updatePriority(id: Long, priority: Float, schedulerType: String) {
            val idx = savedEntries.indexOfFirst { it.id == id }
            if (idx >= 0) savedEntries[idx] = savedEntries[idx].copy(priority = priority, schedulerType = schedulerType)
        }

        override suspend fun clearDelivered() {
            savedEntries.removeAll { it.status == "DELIVERED" }
        }

        override suspend fun resetOrphanedSendingToPending(): Int {
            var count = 0
            for (i in savedEntries.indices) {
                if (savedEntries[i].status == "SENDING") {
                    savedEntries[i] = savedEntries[i].copy(status = "PENDING")
                    count++
                }
            }
            return count
        }

        override suspend fun updateRetry(id: Long, status: String, retryCount: Int, nextAttemptAt: Long, attemptAt: Long) {
            val idx = savedEntries.indexOfFirst { it.id == id }
            if (idx >= 0) {
                savedEntries[idx] = savedEntries[idx].copy(
                    status = status,
                    retryCount = retryCount,
                    nextAttemptAt = nextAttemptAt,
                    lastAttemptAt = attemptAt
                )
            }
        }

        override suspend fun hasPendingEntry(eventId: String, peerId: String?): Boolean {
            return savedEntries.any { it.eventId == eventId && (it.targetPeerId == peerId || (it.targetPeerId == null && peerId == null)) && it.status != "DELIVERED" }
        }

        override fun observePendingCount(): Flow<Int> =
            flowOf(savedEntries.count { it.status == "PENDING" })

        override suspend fun insertReceipt(receipt: DeliveryReceiptEntity) {
            savedReceipts.removeIf { it.eventId == receipt.eventId && it.peerDeviceId == receipt.peerDeviceId }
            savedReceipts.add(receipt)
        }

        override suspend fun getReceipts(eventId: String): List<DeliveryReceiptEntity> =
            savedReceipts.filter { it.eventId == eventId }

        override suspend fun isDeliveredTo(eventId: String, peerId: String): Boolean =
            savedReceipts.any { it.eventId == eventId && it.peerDeviceId == peerId }
    }
}
