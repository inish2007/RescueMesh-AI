package com.rescuemesh.ai.evidence

import com.rescuemesh.ai.data.dao.ObservationDao
import com.rescuemesh.ai.data.dao.OutboxDao
import com.rescuemesh.ai.data.dao.SequenceDigest
import com.rescuemesh.ai.data.entity.DeliveryReceiptEntity
import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.data.entity.OutboxEntity
import com.rescuemesh.ai.data.repository.ObservationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Level 3 Adversarial & Failure Testing Suite:
 * Rigorously simulates failure modes, user cancellations, corrupt/empty camera outputs,
 * physical file absence, cryptographic tampering, directory traversal, and rapid repeat submissions.
 */
class EvidenceAdversarialTest {

    private lateinit var tempDir: File
    private lateinit var evidenceManager: FakeEvidenceManager
    private lateinit var obsDao: AdversarialTestObservationDao
    private lateinit var outboxDao: AdversarialTestOutboxDao
    private lateinit var repository: ObservationRepository

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "rescuemesh_adv_${UUID.randomUUID().toString().take(8)}")
        tempDir.mkdirs()
        evidenceManager = FakeEvidenceManager(tempDir)
        obsDao = AdversarialTestObservationDao()
        outboxDao = AdversarialTestOutboxDao()
        repository = ObservationRepository(
            observationDao = obsDao,
            outboxDao = outboxDao,
            deviceId = "ADV_NODE_01",
            missionId = "MISSION_ADVERSARIAL"
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // 1. Cancelled Camera Capture — Zero Orphaned Files
    // =========================================================================

    @Test
    fun testCancelledCameraCaptureLeavesNoOrphanedFiles() {
        // Step 1: User initiates capture intent; temporary file placeholder is created
        val (uri, tempFile) = evidenceManager.createEvidenceUri()
        tempFile.createNewFile() // Simulated OEM camera file creation before shutter
        assertTrue("Temporary file exists before capture resolution", tempFile.exists())

        // Step 2: User cancels camera capture (back pressed) -> cleanupFile called
        val cleanupSuccess = evidenceManager.cleanupFile(tempFile)
        assertTrue("cleanupFile should report successful deletion", cleanupSuccess)
        assertFalse("Temporary file must be deleted", tempFile.exists())

        // Step 3: Verify evidence directory has zero files remaining
        val filesInDir = tempDir.listFiles()?.filter { it.isFile } ?: emptyList()
        assertEquals("Evidence directory must have zero orphaned files after cancellation", 0, filesInDir.size)
    }

    // =========================================================================
    // 2. Corrupted / 0-Byte Camera Output — Rejection & Disk Purge
    // =========================================================================

    @Test
    fun testCorruptedZeroByteFileIsPurgedFromDiskAndRejectedFromPersistence() = runBlocking {
        // Step 1: Camera writes 0 bytes due to storage exhaustion, crash, or abort
        val corruptFile = File(tempDir, "corrupted_zero_byte.jpg").apply {
            createNewFile()
        }
        assertTrue("Precondition: 0-byte file exists on disk", corruptFile.exists())
        assertEquals(0L, corruptFile.length())

        // Step 2: Caller or attacker attempts to submit this file with an arbitrary hash
        val obs = repository.createObservation(
            eventType = "HAZARD",
            title = "Corrupt photo submission",
            evidencePath = corruptFile.absolutePath,
            evidenceHash = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
        )

        // Step 3: Invariant checks
        assertNull("evidencePath must be set to null for 0-byte corrupt file", obs.evidencePath)
        assertNull("evidenceHash must be set to null for 0-byte corrupt file", obs.evidenceHash)
        assertFalse("Corrupt 0-byte file must be deleted from storage", corruptFile.exists())

        // Step 4: Verify persisted database record
        val persisted = obsDao.getById(obs.eventId)
        assertNotNull(persisted)
        assertNull("Persisted evidencePath must be null", persisted?.evidencePath)
        assertNull("Persisted evidenceHash must be null", persisted?.evidenceHash)
    }

    // =========================================================================
    // 3. Physical File Absence — Strict Rejection of Fabricated Hashes
    // =========================================================================

    @Test
    fun testMissingPhysicalFileNeverPersistsEvidenceHash() = runBlocking {
        // Physical file does not exist on disk
        val phantomPath = File(tempDir, "phantom_unreal_file.jpg").absolutePath
        assertFalse("Phantom file must not exist", File(phantomPath).exists())

        val fabricatedSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        val obs = repository.createObservation(
            eventType = "HAZARD",
            title = "Fabricated evidence hash attempt",
            evidencePath = phantomPath,
            evidenceHash = fabricatedSha256
        )

        // Invariant: If physical file is absent, evidenceHash and evidencePath MUST NEVER be persisted
        assertNull("evidencePath must be null when file is missing", obs.evidencePath)
        assertNull("evidenceHash must be null when file is missing", obs.evidenceHash)

        val persisted = obsDao.getById(obs.eventId)
        assertNull("Persisted record must have null evidencePath", persisted?.evidencePath)
        assertNull("Persisted record must have null evidenceHash", persisted?.evidenceHash)
    }

    // =========================================================================
    // 4. Evidence Tampering Detection — Cryptographic Mismatch
    // =========================================================================

    @Test
    fun testEvidenceTamperingIsCryptographicallyDetected() {
        val originalBytes = "Original authentic disaster scene photograph byte stream".toByteArray(Charsets.UTF_8)
        val photoFile = File(tempDir, "authentic_photo.jpg").apply { writeBytes(originalBytes) }

        // Compute baseline SHA-256
        val authenticHash = evidenceManager.computeSha256(photoFile)
        assertEquals(64, authenticHash.length)

        // Attacker tampers with the photo file by appending/modifying bytes
        photoFile.appendText(" [UNAUTHORIZED TAMPERING INSERTION]")

        // Recomputed hash
        val tamperedHash = evidenceManager.computeSha256(photoFile)

        assertNotEquals(
            "Tampered file hash MUST differ from original authentic hash",
            authenticHash,
            tamperedHash
        )
    }

    // =========================================================================
    // 5. Directory Traversal & Non-Regular File Attacks
    // =========================================================================

    @Test
    fun testDirectoryAsEvidencePathIsSafelyRejectedWithoutDeletingDirectory() = runBlocking {
        val attackDir = File(tempDir, "traversal_dir").apply { mkdirs() }
        val containedFile = File(attackDir, "safe_marker.txt").apply { writeText("system data") }
        assertTrue(attackDir.isDirectory)

        // computeSha256 must reject directory
        try {
            evidenceManager.computeSha256(attackDir)
            fail("computeSha256 must throw IllegalArgumentException for a directory")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("not a file", ignoreCase = true) == true)
        }

        val obs = repository.createObservation(
            eventType = "NOTE",
            title = "Directory path attack",
            evidencePath = attackDir.absolutePath,
            evidenceHash = "dummyhash"
        )

        assertNull("evidencePath must be null when path points to a directory", obs.evidencePath)
        assertNull("evidenceHash must be null when path points to a directory", obs.evidenceHash)
        assertTrue("Target directory must not be deleted by cleanup logic", attackDir.exists())
        assertTrue("Files inside directory must remain intact", containedFile.exists())
    }

    // =========================================================================
    // 6. Rapid Concurrent Repeat Submissions
    // =========================================================================

    @Test
    fun testRapidConcurrentSubmissionsMaintainIntegrityAndMonotonicity() = runBlocking {
        repository.initSequence()
        val numThreads = 20

        val deferredList = (1..numThreads).map { index ->
            async(Dispatchers.Default) {
                val photoBytes = "Concurrent Photo Payload #$index ${UUID.randomUUID()}".toByteArray(Charsets.UTF_8)
                val file = File(tempDir, "rapid_photo_$index.jpg").apply { writeBytes(photoBytes) }
                val hash = evidenceManager.computeSha256(file)

                val obs = repository.createObservation(
                    eventType = "HAZARD",
                    title = "Rapid Hazard #$index",
                    evidencePath = file.absolutePath,
                    evidenceHash = hash
                )
                Triple(obs, file, hash)
            }
        }

        val results = deferredList.awaitAll()

        // 1. All event IDs must be distinct
        val eventIds = results.map { it.first.eventId }.toSet()
        assertEquals("All 20 event IDs must be unique", numThreads, eventIds.size)

        // 2. All sequence numbers must be distinct and span 1..20
        val sequenceNumbers = results.map { it.first.authorSequence }.toSet()
        assertEquals("All 20 sequences must be unique", numThreads, sequenceNumbers.size)
        assertEquals("Max sequence must be 20", 20L, sequenceNumbers.maxOrNull())

        // 3. Every observation has correct matching hash and persists
        for ((obs, file, expectedHash) in results) {
            assertEquals("Observation hash must match computed file hash", expectedHash, obs.evidenceHash)
            assertEquals(file.absolutePath, obs.evidencePath)

            val persisted = obsDao.getById(obs.eventId)
            assertNotNull("Observation must be persisted in DAO", persisted)
            assertEquals(expectedHash, persisted?.evidenceHash)
        }

        // 4. Outbox entries count equals number of submissions
        val outbox = outboxDao.getPendingByPriority()
        assertEquals("Outbox must hold exactly 20 pending items", numThreads, outbox.size)
    }

    // =========================================================================
    // 7. Large File Streaming Buffer Verification
    // =========================================================================

    @Test
    fun testLargeMultiMegabyteStreamingBufferSha256() {
        // Create 256 KB file with non-repeating data patterns to thoroughly stress buffer iteration
        val payloadSize = 262144 // 256 KB
        val largeBytes = ByteArray(payloadSize) { idx -> ((idx * 31) % 256).toByte() }
        val largeFile = File(tempDir, "large_sample.jpg").apply { writeBytes(largeBytes) }

        val streamingHash = evidenceManager.computeSha256(largeFile)

        // Compare against standard in-memory single-pass Java MessageDigest
        val singlePassDigest = MessageDigest.getInstance("SHA-256").digest(largeBytes)
        val expectedHex = singlePassDigest.joinToString("") { "%02x".format(it) }

        assertEquals("256KB streaming calculation must match single-pass digest exactly", expectedHex, streamingHash)
    }

    // =========================================================================
    // Test DAO Implementations
    // =========================================================================

    private class AdversarialTestObservationDao : ObservationDao {
        private val storage = ConcurrentHashMap<String, ObservationEntity>()

        override suspend fun insert(observation: ObservationEntity) {
            storage[observation.eventId] = observation
        }

        override suspend fun insertAll(observations: List<ObservationEntity>) {
            observations.forEach { storage[it.eventId] = it }
        }

        override suspend fun getById(eventId: String): ObservationEntity? = storage[eventId]

        override suspend fun exists(eventId: String): Boolean = storage.containsKey(eventId)

        override suspend fun getLatestSequence(authorDeviceId: String): Long {
            return storage.values
                .filter { it.authorDeviceId == authorDeviceId }
                .maxOfOrNull { it.authorSequence } ?: 0L
        }

        override suspend fun getAllSequenceDigests(): List<SequenceDigest> {
            return storage.values
                .groupBy { it.authorDeviceId }
                .map { (author, list) ->
                    SequenceDigest(
                        authorDeviceId = author,
                        maxSeq = list.maxOfOrNull { it.authorSequence } ?: 0L
                    )
                }
        }

        override fun observeAll(missionId: String): Flow<List<ObservationEntity>> {
            return flowOf(storage.values.filter { it.missionId == missionId })
        }

        override fun observeCount(missionId: String): Flow<Int> {
            return flowOf(storage.values.count { it.missionId == missionId })
        }

        override fun observeLocalCount(missionId: String): Flow<Int> {
            return flowOf(storage.values.count { it.missionId == missionId && it.isLocal })
        }

        override fun observeReceivedCount(missionId: String): Flow<Int> {
            return flowOf(storage.values.count { it.missionId == missionId && !it.isLocal })
        }

        override suspend fun getAll(missionId: String): List<ObservationEntity> {
            return storage.values.filter { it.missionId == missionId }
        }

        override suspend fun getAfterSequence(authorDeviceId: String, afterSequence: Long): List<ObservationEntity> {
            return storage.values
                .filter { it.authorDeviceId == authorDeviceId && it.authorSequence > afterSequence }
                .sortedBy { it.authorSequence }
        }

        override suspend fun getByEdgeId(edgeId: String): List<ObservationEntity> {
            return storage.values
                .filter { it.mapEdgeId == edgeId }
                .sortedByDescending { it.observedAtDevice }
        }

        override suspend fun updateEvidence(eventId: String, evidencePath: String?, verificationStatus: String) {
            storage[eventId]?.let { existing ->
                storage[eventId] = existing.copy(
                    evidencePath = evidencePath,
                    verificationStatus = verificationStatus
                )
            }
        }
    }

    private class AdversarialTestOutboxDao : OutboxDao {
        private val entries = ConcurrentHashMap<Long, OutboxEntity>()
        private val nextId = java.util.concurrent.atomic.AtomicLong(1L)

        override suspend fun insert(outbox: OutboxEntity): Long {
            val id = nextId.getAndIncrement()
            entries[id] = outbox.copy(id = id)
            return id
        }

        override suspend fun insertAll(entries: List<OutboxEntity>) {
            entries.forEach { insert(it) }
        }

        override suspend fun getPendingByPriority(): List<OutboxEntity> =
            entries.values.filter { it.status == "PENDING" }.sortedByDescending { it.priority }

        override suspend fun getPendingByFifo(): List<OutboxEntity> =
            entries.values.filter { it.status == "PENDING" }.sortedBy { it.createdAt }

        override suspend fun getPendingForPeer(peerId: String): List<OutboxEntity> =
            entries.values.filter { it.status == "PENDING" && (it.targetPeerId == null || it.targetPeerId == peerId) }

        override fun observeRecent(limit: Int): Flow<List<OutboxEntity>> =
            flowOf(entries.values.sortedByDescending { it.createdAt }.take(limit))

        override suspend fun updateStatus(id: Long, status: String, attemptAt: Long) {
            val existing = entries[id] ?: return
            entries[id] = existing.copy(status = status, lastAttemptAt = attemptAt)
        }

        override suspend fun updateStatusByEvent(eventId: String, status: String, attemptAt: Long) {
            entries.values.filter { it.eventId == eventId }.forEach {
                entries[it.id] = it.copy(status = status, lastAttemptAt = attemptAt)
            }
        }

        override suspend fun updateStatusForPeer(eventId: String, peerId: String, status: String, attemptAt: Long) {
            entries.values.filter { it.eventId == eventId && it.targetPeerId == peerId }.forEach {
                entries[it.id] = it.copy(status = status, lastAttemptAt = attemptAt)
            }
        }

        override suspend fun claimSending(id: Long, now: Long, ackDeadline: Long): Int {
            var claimed = 0
            entries.computeIfPresent(id) { _, entry ->
                if (entry.status == "PENDING") {
                    claimed = 1
                    entry.copy(
                        status = "SENDING",
                        retryCount = entry.retryCount + 1,
                        lastAttemptAt = now,
                        nextAttemptAt = ackDeadline
                    )
                } else entry
            }
            return claimed
        }

        override suspend fun requeueExpiredSending(now: Long): Int {
            var count = 0
            for ((id, entry) in entries) {
                if (entry.status == "SENDING" && entry.nextAttemptAt <= now) {
                    val newStatus = if (entry.retryCount >= entry.maxRetries && entry.priority < 0.8f) "FAILED" else "PENDING"
                    entries[id] = entry.copy(status = newStatus)
                    count++
                }
            }
            return count
        }

        override suspend fun updatePriority(id: Long, priority: Float, schedulerType: String) {
            val existing = entries[id] ?: return
            entries[id] = existing.copy(priority = priority, schedulerType = schedulerType)
        }

        override suspend fun clearDelivered() {
            entries.values.removeIf { it.status == "DELIVERED" }
        }

        override suspend fun resetOrphanedSendingToPending(): Int {
            var count = 0
            for ((id, entry) in entries) {
                if (entry.status == "SENDING") {
                    entries[id] = entry.copy(status = "PENDING")
                    count++
                }
            }
            return count
        }

        override suspend fun updateRetry(id: Long, status: String, retryCount: Int, nextAttemptAt: Long, attemptAt: Long) {
            val existing = entries[id] ?: return
            entries[id] = existing.copy(
                status = status,
                retryCount = retryCount,
                nextAttemptAt = nextAttemptAt,
                lastAttemptAt = attemptAt
            )
        }

        override suspend fun hasPendingEntry(eventId: String, peerId: String?): Boolean {
            return entries.values.any { it.eventId == eventId && (it.targetPeerId == peerId || (it.targetPeerId == null && peerId == null)) && it.status != "DELIVERED" }
        }

        override fun observePendingCount(): Flow<Int> =
            flowOf(entries.values.count { it.status == "PENDING" })

        override suspend fun insertReceipt(receipt: DeliveryReceiptEntity) {}
        override suspend fun getReceipts(eventId: String): List<DeliveryReceiptEntity> = emptyList()
        override suspend fun isDeliveredTo(eventId: String, peerId: String): Boolean = false
    }
}
