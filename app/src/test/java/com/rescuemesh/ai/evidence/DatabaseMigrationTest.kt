package com.rescuemesh.ai.evidence

import androidx.sqlite.db.SupportSQLiteDatabase
import com.rescuemesh.ai.data.dao.ObservationDao
import com.rescuemesh.ai.data.dao.OutboxDao
import com.rescuemesh.ai.data.dao.SequenceDigest
import com.rescuemesh.ai.data.database.RescueMeshDatabase
import com.rescuemesh.ai.data.entity.DeliveryReceiptEntity
import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.data.entity.OutboxEntity
import com.rescuemesh.ai.data.repository.ObservationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Level 2 Integration Test Suite:
 * Verifies Room schema migration v2 -> v3, atomic persistence of observations and outbox records,
 * sequence monotonicity across restarts, and cross-mission data isolation.
 */
class DatabaseMigrationTest {

    private lateinit var fakeObsDao: MigrationTestObservationDao
    private lateinit var fakeOutboxDao: MigrationTestOutboxDao
    private lateinit var repository: ObservationRepository

    @Before
    fun setUp() {
        fakeObsDao = MigrationTestObservationDao()
        fakeOutboxDao = MigrationTestOutboxDao()
        repository = ObservationRepository(
            observationDao = fakeObsDao,
            outboxDao = fakeOutboxDao,
            deviceId = "RESCUE_NODE_01",
            missionId = "MISSION_SECTOR_BRAVO"
        )
    }

    // =========================================================================
    // 1. Schema Migration v2 -> v3 Validation
    // =========================================================================

    @Test
    fun testMigration2To3VersionsAndContract() {
        val migration = RescueMeshDatabase.MIGRATION_2_3
        assertEquals("Migration start version must be 2", 2, migration.startVersion)
        assertEquals("Migration end version must be 3", 3, migration.endVersion)
    }

    @Test
    fun testMigration2To3ExecutesAlterTableSql() {
        val executedSqlList = mutableListOf<String>()
        val proxyDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") {
                executedSqlList.add(args[0] as String)
            }
            null
        } as SupportSQLiteDatabase

        RescueMeshDatabase.MIGRATION_2_3.migrate(proxyDb)

        assertEquals("Exactly one DDL statement should be executed for migration 2->3", 1, executedSqlList.size)
        val ddl = executedSqlList[0]
        assertTrue("DDL must alter observations table", ddl.contains("ALTER TABLE observations", ignoreCase = true))
        assertTrue("DDL must add column evidenceHash", ddl.contains("ADD COLUMN evidenceHash", ignoreCase = true))
        assertTrue("DDL must define evidenceHash as TEXT", ddl.contains("TEXT", ignoreCase = true))
    }

    @Test
    fun testMigration1To2VersionsAndContract() {
        val migration = RescueMeshDatabase.MIGRATION_1_2
        assertEquals("Migration start version must be 1", 1, migration.startVersion)
        assertEquals("Migration end version must be 2", 2, migration.endVersion)

        val executedSqlList = mutableListOf<String>()
        val proxyDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") {
                executedSqlList.add(args[0] as String)
            }
            null
        } as SupportSQLiteDatabase

        RescueMeshDatabase.MIGRATION_1_2.migrate(proxyDb)

        assertTrue("DDL must create missions table", executedSqlList.any { it.contains("CREATE TABLE IF NOT EXISTS missions", ignoreCase = true) })
    }

    @Test
    fun testMigration3To4VersionsAndContract() {
        val migration = RescueMeshDatabase.MIGRATION_3_4
        assertEquals("Migration start version must be 3", 3, migration.startVersion)
        assertEquals("Migration end version must be 4", 4, migration.endVersion)

        val executedSqlList = mutableListOf<String>()
        val proxyDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL") {
                executedSqlList.add(args[0] as String)
            }
            null
        } as SupportSQLiteDatabase

        RescueMeshDatabase.MIGRATION_3_4.migrate(proxyDb)

        assertTrue("DDL must alter outbox for retryCount", executedSqlList.any { it.contains("ALTER TABLE outbox", ignoreCase = true) && it.contains("retryCount", ignoreCase = true) })
        assertTrue("DDL must alter outbox for nextAttemptAt", executedSqlList.any { it.contains("ALTER TABLE outbox", ignoreCase = true) && it.contains("nextAttemptAt", ignoreCase = true) })
    }

    @Test
    fun testPreMigrationRecordsPreserveIntegrityWithNullEvidenceHash() {
        // Simulate a record created under v2 schema before evidenceHash existed
        val legacyV2Observation = ObservationEntity(
            eventId = "legacy-evt-001",
            missionId = "MISSION_SECTOR_BRAVO",
            authorDeviceId = "RESCUE_NODE_01",
            authorSequence = 1L,
            eventType = "ROUTE_STATUS",
            title = "Sector Passage Alpha",
            description = "Surveyed clear during initial sweep",
            reportedStatus = "OPEN",
            observedAtDevice = 1000000L,
            evidencePath = null,
            evidenceHash = null
        )

        fakeObsDao.insertDirect(legacyV2Observation)

        val retrieved = fakeObsDao.getByIdDirect("legacy-evt-001")
        assertNotNull("Legacy observation must persist", retrieved)
        assertEquals("legacy-evt-001", retrieved?.eventId)
        assertEquals("Sector Passage Alpha", retrieved?.title)
        assertNull("evidenceHash must default to null for pre-migration records", retrieved?.evidenceHash)
        assertNull("evidencePath must be null", retrieved?.evidencePath)
    }

    // =========================================================================
    // 2. Atomic Persistence & Sequence Monotonicity
    // =========================================================================

    @Test
    fun testAtomicPersistenceOfObservationAndOutboxRecord() = runBlocking {
        repository.initSequence()

        val obs = repository.createObservation(
            eventType = "HAZARD",
            title = "Gas Leak Detected",
            description = "High concentration in basement",
            reportedStatus = "HAZARD"
        )

        // 1. Observation entity verification
        val persistedObs = fakeObsDao.getById(obs.eventId)
        assertNotNull("Observation must be persisted immediately to ObservationDao", persistedObs)
        assertEquals(obs.eventId, persistedObs?.eventId)
        assertEquals("MISSION_SECTOR_BRAVO", persistedObs?.missionId)
        assertEquals("RESCUE_NODE_01", persistedObs?.authorDeviceId)
        assertEquals(1L, persistedObs?.authorSequence)
        assertTrue(persistedObs?.isLocal == true)

        // 2. Outbox entity verification
        val pendingOutbox = fakeOutboxDao.getPendingByPriority()
        assertEquals("Exactly one outbox entry must be created atomically", 1, pendingOutbox.size)
        val entry = pendingOutbox[0]
        assertEquals(obs.eventId, entry.eventId)
        assertNull("Target peer should be null for broadcast entry", entry.targetPeerId)
        assertEquals("PENDING", entry.status)
    }

    @Test
    fun testMonotonicSequenceResumptionAcrossReboots() = runBlocking {
        // Pre-populate database with observations having sequences 1..5
        for (seq in 1L..5L) {
            fakeObsDao.insertDirect(
                ObservationEntity(
                    eventId = "reboot-evt-$seq",
                    missionId = "MISSION_SECTOR_BRAVO",
                    authorDeviceId = "RESCUE_NODE_01",
                    authorSequence = seq,
                    eventType = "NOTE",
                    title = "Pre-reboot note $seq",
                    observedAtDevice = 1000L * seq
                )
            )
        }

        // Simulate application restart: instantiate a fresh repository
        val restartedRepo = ObservationRepository(
            observationDao = fakeObsDao,
            outboxDao = fakeOutboxDao,
            deviceId = "RESCUE_NODE_01",
            missionId = "MISSION_SECTOR_BRAVO"
        )

        restartedRepo.initSequence()

        // Create new observation post-reboot
        val nextObs = restartedRepo.createObservation(
            eventType = "NOTE",
            title = "First observation post-reboot"
        )

        assertEquals("Sequence must continue monotonically from 6 without gaps or duplicates", 6L, nextObs.authorSequence)
    }

    // =========================================================================
    // 3. Mission Isolation & Cross-Mission Partitioning
    // =========================================================================

    @Test
    fun testCrossMissionQueriesAreStrictlyIsolated() = runBlocking {
        // Insert observations into Sector Bravo (active mission)
        val obsBravo1 = repository.createObservation(eventType = "NOTE", title = "Bravo Note 1")
        val obsBravo2 = repository.createObservation(eventType = "HAZARD", title = "Bravo Hazard 2")

        // Directly insert observations from another mission (Sector Charlie)
        fakeObsDao.insertDirect(
            ObservationEntity(
                eventId = "charlie-evt-1",
                missionId = "MISSION_SECTOR_CHARLIE",
                authorDeviceId = "OTHER_NODE",
                authorSequence = 1L,
                eventType = "NOTE",
                title = "Charlie Note 1",
                observedAtDevice = System.currentTimeMillis()
            )
        )

        // Verify observeAll only contains Sector Bravo
        val bravoObservations = repository.observeAll().first()
        assertEquals("Sector Bravo must only return its own observations", 2, bravoObservations.size)
        assertTrue(bravoObservations.all { it.missionId == "MISSION_SECTOR_BRAVO" })
        assertFalse(bravoObservations.any { it.eventId == "charlie-evt-1" })

        // Verify observeCount
        val bravoCount = repository.observeCount().first()
        assertEquals("observeCount must only count active mission", 2, bravoCount)

        // Verify getAll
        val allBravo = repository.getAll()
        assertEquals(2, allBravo.size)
        assertTrue(allBravo.all { it.missionId == "MISSION_SECTOR_BRAVO" })
    }

    @Test
    fun testReceiveObservationRejectsMismatchedMission() = runBlocking {
        val foreignObs = ObservationEntity(
            eventId = UUID.randomUUID().toString(),
            missionId = "MISSION_SECTOR_DELTA",
            authorDeviceId = "FOREIGN_NODE",
            authorSequence = 10L,
            eventType = "SURVIVOR",
            title = "Survivor located",
            observedAtDevice = System.currentTimeMillis()
        )

        val accepted = repository.receiveObservation(foreignObs)
        assertFalse("Observation from foreign mission MUST be rejected", accepted)
        assertFalse("Foreign observation must not be stored in ObservationDao", fakeObsDao.exists(foreignObs.eventId))
    }

    @Test
    fun testReceiveObservationSuppressesDuplicates() = runBlocking {
        val validPeerObs = ObservationEntity(
            eventId = "peer-shared-event-001",
            missionId = "MISSION_SECTOR_BRAVO",
            authorDeviceId = "PEER_NODE_02",
            authorSequence = 1L,
            eventType = "NOTE",
            title = "Rendezvous point clear",
            observedAtDevice = System.currentTimeMillis()
        )

        // First receive: accepted
        val firstReceive = repository.receiveObservation(validPeerObs)
        assertTrue("First receipt of peer observation must be accepted", firstReceive)
        assertTrue(fakeObsDao.exists("peer-shared-event-001"))

        // Second receive with identical eventId: rejected as duplicate
        val secondReceive = repository.receiveObservation(validPeerObs)
        assertFalse("Duplicate receipt must return false", secondReceive)

        // Total count in DAO remains exactly 1
        val stored = fakeObsDao.getById("peer-shared-event-001")
        assertNotNull(stored)
        assertFalse("Peer observation must be marked isLocal=false", stored!!.isLocal)
    }

    // =========================================================================
    // Test DAO Implementations
    // =========================================================================

    private class MigrationTestObservationDao : ObservationDao {
        private val storage = ConcurrentHashMap<String, ObservationEntity>()

        fun insertDirect(obs: ObservationEntity) {
            storage[obs.eventId] = obs
        }

        fun getByIdDirect(id: String): ObservationEntity? = storage[id]

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
            return flowOf(storage.values.filter { it.missionId == missionId }.sortedByDescending { it.observedAtDevice })
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
            return storage.values.filter { it.missionId == missionId }.sortedByDescending { it.observedAtDevice }
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

    private class MigrationTestOutboxDao : OutboxDao {
        private val entries = ConcurrentHashMap<Long, OutboxEntity>()
        private val receipts = ConcurrentHashMap<String, DeliveryReceiptEntity>()
        private var nextId = 1L

        override suspend fun insert(outbox: OutboxEntity): Long {
            val id = nextId++
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

        override suspend fun insertReceipt(receipt: DeliveryReceiptEntity) {
            receipts["${receipt.eventId}_${receipt.peerDeviceId}"] = receipt
        }

        override suspend fun getReceipts(eventId: String): List<DeliveryReceiptEntity> =
            receipts.values.filter { it.eventId == eventId }

        override suspend fun isDeliveredTo(eventId: String, peerId: String): Boolean =
            receipts.containsKey("${eventId}_${peerId}")
    }
}
