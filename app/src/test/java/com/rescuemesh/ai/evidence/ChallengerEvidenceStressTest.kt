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
import java.util.concurrent.atomic.AtomicLong

/**
 * Empirical Challenger 1 Stress Harness:
 * Rigorously challenges photo evidence hashing, edge cases, directory traversal,
 * file deletion side effects, and concurrency safety.
 */
class ChallengerEvidenceStressTest {

    private lateinit var tempDir: File
    private lateinit var evidenceManager: FakeEvidenceManager
    private lateinit var obsDao: StressTestObservationDao
    private lateinit var outboxDao: StressTestOutboxDao
    private lateinit var repository: ObservationRepository

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "rescuemesh_challenger_${UUID.randomUUID().toString().take(8)}")
        tempDir.mkdirs()
        evidenceManager = FakeEvidenceManager(tempDir)
        obsDao = StressTestObservationDao()
        outboxDao = StressTestOutboxDao()
        repository = ObservationRepository(
            observationDao = obsDao,
            outboxDao = outboxDao,
            deviceId = "CHALLENGER_NODE",
            missionId = "CHALLENGE_MISSION"
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // 1. Cryptographic NIST CAVP / FIPS 180-4 Test Vectors & Boundary Conditions
    // =========================================================================

    @Test
    fun testNistCavpLongAndBoundaryTestVectors() {
        // NIST Vector: 56-byte string crossing 512-bit block boundary
        val block56Bytes = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".toByteArray(Charsets.UTF_8)
        val file56 = File(tempDir, "block56.jpg").apply { writeBytes(block56Bytes) }
        val hash56 = evidenceManager.computeSha256(file56)
        assertEquals(
            "NIST 56-byte vector crossing block boundary must match standard",
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hash56
        )

        // Buffer boundary vectors for 8192-byte buffer:
        // Case A: 8191 bytes (buffer - 1)
        val bytes8191 = ByteArray(8191) { 0x5A }
        val file8191 = File(tempDir, "b8191.jpg").apply { writeBytes(bytes8191) }
        val hash8191 = evidenceManager.computeSha256(file8191)
        val expected8191 = MessageDigest.getInstance("SHA-256").digest(bytes8191).joinToString("") { "%02x".format(it) }
        assertEquals(expected8191, hash8191)

        // Case B: Exactly 8192 bytes (buffer boundary)
        val bytes8192 = ByteArray(8192) { 0x5A }
        val file8192 = File(tempDir, "b8192.jpg").apply { writeBytes(bytes8192) }
        val hash8192 = evidenceManager.computeSha256(file8192)
        val expected8192 = MessageDigest.getInstance("SHA-256").digest(bytes8192).joinToString("") { "%02x".format(it) }
        assertEquals(expected8192, hash8192)

        // Case C: 8193 bytes (buffer + 1)
        val bytes8193 = ByteArray(8193) { 0x5A }
        val file8193 = File(tempDir, "b8193.jpg").apply { writeBytes(bytes8193) }
        val hash8193 = evidenceManager.computeSha256(file8193)
        val expected8193 = MessageDigest.getInstance("SHA-256").digest(bytes8193).joinToString("") { "%02x".format(it) }
        assertEquals(expected8193, hash8193)

        // NIST 1,000,000 repetitions of 'a' (1 MB standard NIST vector)
        val millionA = ByteArray(1000000) { 'a'.code.toByte() }
        val millionFile = File(tempDir, "million_a.jpg").apply { writeBytes(millionA) }
        val millionHash = evidenceManager.computeSha256(millionFile)
        assertEquals(
            "NIST 1,000,000 'a's standard test vector must match FIPS 180-4",
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            millionHash
        )
    }

    @Test
    fun testAvalancheEffectAndBitCorruption() {
        val baseData = ByteArray(16384) { (it % 256).toByte() }
        val baseFile = File(tempDir, "base_photo.jpg").apply { writeBytes(baseData) }
        val baseHash = evidenceManager.computeSha256(baseFile)

        // Single bit flip at byte index 0 (0th bit)
        val bitFlippedData = baseData.clone()
        bitFlippedData[0] = (bitFlippedData[0].toInt() xor 1).toByte()
        val flippedFile = File(tempDir, "flipped.jpg").apply { writeBytes(bitFlippedData) }
        val flippedHash = evidenceManager.computeSha256(flippedFile)

        assertNotEquals(baseHash, flippedHash)

        // Count differing hex characters (avalanche effect should change ~50% of bits/chars)
        var differingChars = 0
        for (i in baseHash.indices) {
            if (baseHash[i] != flippedHash[i]) differingChars++
        }
        assertTrue("Avalanche effect must alter majority of hash characters (changed: $differingChars/64)", differingChars >= 32)
    }

    // =========================================================================
    // 2. Adversarial Edge Cases: 0-Byte, Missing, and Cleanup
    // =========================================================================

    @Test
    fun testZeroByteFileThrowsByDefaultAndAllowsWhenExplicit() {
        val emptyFile = File(tempDir, "empty.jpg").apply { createNewFile() }

        // Must throw by default
        assertThrows(IllegalArgumentException::class.java) {
            evidenceManager.computeSha256(emptyFile)
        }

        // Must throw when allowEmpty is explicitly false
        assertThrows(IllegalArgumentException::class.java) {
            evidenceManager.computeSha256(emptyFile, allowEmpty = false)
        }

        // Must succeed when allowEmpty is true and return NIST empty hash
        val emptyHash = evidenceManager.computeSha256(emptyFile, allowEmpty = true)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", emptyHash)
    }

    @Test
    fun testNonExistentFileThrowsEvenIfAllowEmptyIsTrue() {
        val missing = File(tempDir, "does_not_exist.jpg")
        assertFalse(missing.exists())

        assertThrows(IllegalArgumentException::class.java) {
            evidenceManager.computeSha256(missing, allowEmpty = true)
        }
    }

    @Test
    fun testCleanupIsIdempotentAndSafe() {
        val dummy = File(tempDir, "temp_target.jpg").apply { writeText("evidence") }
        assertTrue(dummy.exists())

        assertTrue(evidenceManager.cleanupFile(dummy))
        assertFalse(dummy.exists())
        assertFalse("Second cleanup must return false without throwing", evidenceManager.cleanupFile(dummy))
    }

    // =========================================================================
    // 3. ObservationRepository Persistence Integrity & Vulnerability Probes
    // =========================================================================

    @Test
    fun testObservationRepositoryRejectsMissingAndZeroByteFiles() = runBlocking {
        // Probe 1: Non-existent file
        val missingPath = File(tempDir, "missing.jpg").absolutePath
        val obsMissing = repository.createObservation(
            eventType = "HAZARD",
            title = "Missing File",
            evidencePath = missingPath,
            evidenceHash = "somehash"
        )
        assertNull("evidencePath must be null when file does not exist", obsMissing.evidencePath)
        assertNull("evidenceHash must be null when file does not exist", obsMissing.evidenceHash)

        // Probe 2: Zero-byte file
        val zeroFile = File(tempDir, "zero.jpg").apply { createNewFile() }
        val obsZero = repository.createObservation(
            eventType = "HAZARD",
            title = "Zero Byte File",
            evidencePath = zeroFile.absolutePath,
            evidenceHash = "somehash"
        )
        assertNull("evidencePath must be null when file is empty", obsZero.evidencePath)
        assertNull("evidenceHash must be null when file is empty", obsZero.evidenceHash)
        assertFalse("0-byte file must be deleted", zeroFile.exists())

        // Probe 3: Null evidencePath with non-null evidenceHash
        val obsNullPath = repository.createObservation(
            eventType = "NOTE",
            title = "Null path with hash",
            evidencePath = null,
            evidenceHash = "orphan_hash_123"
        )
        assertNull("evidencePath is null", obsNullPath.evidencePath)
        assertNull("evidenceHash must be forced to null if evidencePath is null", obsNullPath.evidenceHash)
    }

    /**
     * VULNERABILITY PROBE:
     * ObservationRepository.createObservation() calls file.delete() on any path where
     * file.exists() is true and !(file.isFile && file.length() > 0L).
     * If evidencePath points to an empty directory outside the evidence storage,
     * it will UNINTENTIONALLY DELETE that empty directory!
     */
    @Test
    fun testObservationRepositoryDeletesEmptyDirectoryVulnerabilityProbe() = runBlocking {
        val externalEmptyDir = File(tempDir, "external_system_dir").apply { mkdirs() }
        assertTrue("Precondition: empty directory exists", externalEmptyDir.exists())
        assertTrue("Precondition: is directory", externalEmptyDir.isDirectory)

        // Attempt observation creation with directory as evidencePath
        val obs = repository.createObservation(
            eventType = "NOTE",
            title = "Directory as evidencePath",
            evidencePath = externalEmptyDir.absolutePath,
            evidenceHash = "bogushash"
        )

        assertNull("evidencePath must be null", obs.evidencePath)
        assertNull("evidenceHash must be null", obs.evidenceHash)

        // Check if ObservationRepository deleted the directory:
        val dirStillExists = externalEmptyDir.exists()
        // Record empirical observation: In ObservationRepository.kt lines 68-70:
        // "if (file.exists()) { file.delete() }"
        // Because externalEmptyDir is an empty directory, file.delete() executes and deletes it!
        println("Empirical finding: externalEmptyDir exists after createObservation? $dirStillExists")
        // If it was deleted, this demonstrates the unintended deletion side-effect.
    }

    /**
     * VULNERABILITY PROBE:
     * ObservationRepository.createObservation() calls file.delete() on ANY 0-byte file
     * on the filesystem, even if it is completely outside the evidence folder.
     */
    @Test
    fun testObservationRepositoryDeletesExternalZeroByteFileProbe() = runBlocking {
        val externalZeroByteFile = File(tempDir, "critical_external.lock").apply { createNewFile() }
        assertTrue(externalZeroByteFile.exists())
        assertEquals(0L, externalZeroByteFile.length())

        val obs = repository.createObservation(
            eventType = "NOTE",
            title = "External lockfile attack",
            evidencePath = externalZeroByteFile.absolutePath,
            evidenceHash = "bogus"
        )

        assertNull(obs.evidencePath)
        assertNull(obs.evidenceHash)
        // Check if external lockfile was deleted
        val fileDeleted = !externalZeroByteFile.exists()
        println("Empirical finding: externalZeroByteFile was deleted by ObservationRepository: $fileDeleted")
    }

    // =========================================================================
    // 4. Concurrency Test Double Flakiness Demonstration & Thread-Safe Solution
    // =========================================================================

    /**
     * Demonstrates why Worker Agent C's AdversarialTestOutboxDao was flaky:
     * Unsynchronized `var nextId = 1L; nextId++` suffers data races under coroutine concurrency.
     * With AtomicLong, it guarantees 100% reliability.
     */
    @Test
    fun testDemonstrateUnsynchronizedVsAtomicCounterConcurrency() {
        val numThreads = 50
        val iterations = 100

        // Unsynchronized counter test
        var unsyncedCollisions = 0
        for (iter in 1..iterations) {
            var plainId = 0L
            val ids = ConcurrentHashMap<Long, Boolean>()
            runBlocking {
                val jobs = (1..numThreads).map {
                    async(Dispatchers.Default) {
                        val id = plainId++ // Non-atomic read-modify-write race!
                        ids.put(id, true)
                    }
                }
                jobs.awaitAll()
            }
            if (ids.size < numThreads) {
                unsyncedCollisions++
            }
        }

        // Atomic counter test
        var atomicCollisions = 0
        for (iter in 1..iterations) {
            val atomicId = AtomicLong(0L)
            val ids = ConcurrentHashMap<Long, Boolean>()
            runBlocking {
                val jobs = (1..numThreads).map {
                    async(Dispatchers.Default) {
                        val id = atomicId.incrementAndGet()
                        ids.put(id, true)
                    }
                }
                jobs.awaitAll()
            }
            if (ids.size < numThreads) {
                atomicCollisions++
            }
        }

        println("Concurrency proof: Unsynchronized nextId++ had $unsyncedCollisions collisions out of $iterations runs.")
        println("Concurrency proof: AtomicLong had $atomicCollisions collisions out of $iterations runs.")

        assertTrue("Unsynchronized counter should suffer at least one data race collision under concurrency", unsyncedCollisions > 0)
        assertEquals("Atomic counter must have ZERO collisions", 0, atomicCollisions)
    }

    // =========================================================================
    // Test DAO Implementations (Thread-Safe with AtomicLong)
    // =========================================================================

    private class StressTestObservationDao : ObservationDao {
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

        override fun observeAll(missionId: String): Flow<List<ObservationEntity>> =
            flowOf(storage.values.filter { it.missionId == missionId })

        override fun observeCount(missionId: String): Flow<Int> =
            flowOf(storage.values.count { it.missionId == missionId })

        override fun observeLocalCount(missionId: String): Flow<Int> =
            flowOf(storage.values.count { it.missionId == missionId && it.isLocal })

        override fun observeReceivedCount(missionId: String): Flow<Int> =
            flowOf(storage.values.count { it.missionId == missionId && !it.isLocal })

        override suspend fun getAll(missionId: String): List<ObservationEntity> =
            storage.values.filter { it.missionId == missionId }

        override suspend fun getAfterSequence(authorDeviceId: String, afterSequence: Long): List<ObservationEntity> =
            storage.values.filter { it.authorDeviceId == authorDeviceId && it.authorSequence > afterSequence }

        override suspend fun getByEdgeId(edgeId: String): List<ObservationEntity> =
            storage.values.filter { it.mapEdgeId == edgeId }
    }

    private class StressTestOutboxDao : OutboxDao {
        private val entries = ConcurrentHashMap<Long, OutboxEntity>()
        private val nextId = AtomicLong(1L) // THREAD-SAFE ATOMIC COUNTER

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
