package com.rescuemesh.ai.data

import androidx.sqlite.db.SupportSQLiteDatabase
import com.rescuemesh.ai.data.dao.ChatDao
import com.rescuemesh.ai.data.database.RescueMeshDatabase
import com.rescuemesh.ai.data.entity.ChatMessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit Test Suite for Gate 3 R1:
 * Validates Room v5 Migration (MIGRATION_4_5), ChatMessageEntity schema and constraints,
 * and ChatDao behavior.
 */
class DatabaseMigrationV5Test {

    private lateinit var chatDao: FakeTestChatDao

    @Before
    fun setUp() {
        chatDao = FakeTestChatDao()
    }

    // =========================================================================
    // 1. MIGRATION_4_5 Version & Contract
    // =========================================================================

    @Test
    fun testMigration4To5VersionsAndContract() {
        val migration = RescueMeshDatabase.MIGRATION_4_5
        assertEquals("Migration start version must be 4", 4, migration.startVersion)
        assertEquals("Migration end version must be 5", 5, migration.endVersion)
    }

    @Test
    fun testMigration4To5ExecutesCreateTableAndIndices() {
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

        RescueMeshDatabase.MIGRATION_4_5.migrate(proxyDb)

        // 1 CREATE TABLE + 5 CREATE INDEX = 6 DDL statements
        assertEquals("Exactly 6 DDL statements must be executed for MIGRATION_4_5", 6, executedSqlList.size)

        val createTableSql = executedSqlList[0]
        assertTrue("DDL must create chat_messages table", createTableSql.contains("CREATE TABLE IF NOT EXISTS chat_messages", ignoreCase = true))
        assertTrue("DDL must define id as PRIMARY KEY AUTOINCREMENT", createTableSql.contains("id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL", ignoreCase = true))
        assertTrue("DDL must contain messageId", createTableSql.contains("messageId TEXT NOT NULL", ignoreCase = true))
        assertTrue("DDL must contain missionId", createTableSql.contains("missionId TEXT NOT NULL", ignoreCase = true))
        assertTrue("DDL must contain senderId", createTableSql.contains("senderId TEXT NOT NULL", ignoreCase = true))
        assertTrue("DDL must contain senderName", createTableSql.contains("senderName TEXT NOT NULL", ignoreCase = true))
        assertTrue("DDL must contain text", createTableSql.contains("text TEXT NOT NULL", ignoreCase = true))
        assertTrue("DDL must contain timestamp", createTableSql.contains("timestamp INTEGER NOT NULL", ignoreCase = true))
        assertTrue("DDL must contain status", createTableSql.contains("status TEXT NOT NULL", ignoreCase = true))
        assertTrue("DDL must contain ackPeerCount", createTableSql.contains("ackPeerCount INTEGER NOT NULL", ignoreCase = true))
        assertTrue("DDL must contain isLocal", createTableSql.contains("isLocal INTEGER NOT NULL", ignoreCase = true))
        assertTrue("DDL must contain senderRole", createTableSql.contains("senderRole TEXT NOT NULL", ignoreCase = true))
        assertTrue("DDL must contain linkedEventId", createTableSql.contains("linkedEventId TEXT", ignoreCase = true))
        assertTrue("DDL must contain authorSequence", createTableSql.contains("authorSequence INTEGER NOT NULL", ignoreCase = true))
        assertTrue("DDL must contain relayHopsRemaining", createTableSql.contains("relayHopsRemaining INTEGER NOT NULL", ignoreCase = true))

        val indexStatements = executedSqlList.drop(1)
        assertTrue("Must create UNIQUE index on messageId", indexStatements.any {
            it.contains("CREATE UNIQUE INDEX IF NOT EXISTS index_chat_messages_messageId ON chat_messages(messageId)", ignoreCase = true)
        })
        assertTrue("Must create index on missionId", indexStatements.any {
            it.contains("CREATE INDEX IF NOT EXISTS index_chat_messages_missionId ON chat_messages(missionId)", ignoreCase = true)
        })
        assertTrue("Must create index on timestamp", indexStatements.any {
            it.contains("CREATE INDEX IF NOT EXISTS index_chat_messages_timestamp ON chat_messages(timestamp)", ignoreCase = true)
        })
        assertTrue("Must create index on status", indexStatements.any {
            it.contains("CREATE INDEX IF NOT EXISTS index_chat_messages_status ON chat_messages(status)", ignoreCase = true)
        })
        assertTrue("Must create index on linkedEventId", indexStatements.any {
            it.contains("CREATE INDEX IF NOT EXISTS index_chat_messages_linkedEventId ON chat_messages(linkedEventId)", ignoreCase = true)
        })
    }

    // =========================================================================
    // 2. ChatMessageEntity Defaults & Field Constraints
    // =========================================================================

    @Test
    fun testChatMessageEntityDefaultsAndRoles() {
        val msg = ChatMessageEntity(
            messageId = "msg-001",
            missionId = "MISSION_ALPHA",
            senderId = "DEV_01",
            senderName = "Operator Alpha",
            text = "Emergency report for sector 4",
            timestamp = 1700000000L,
            status = ChatMessageEntity.STATUS_QUEUED
        )

        assertEquals(0L, msg.id)
        assertEquals(0, msg.ackPeerCount)
        assertFalse(msg.isLocal)
        assertEquals(ChatMessageEntity.ROLE_UNVERIFIED, msg.senderRole)
        assertNull(msg.linkedEventId)
        assertEquals(0L, msg.authorSequence)
        assertEquals(3, msg.relayHopsRemaining)
        assertEquals(500, ChatMessageEntity.MAX_TEXT_LENGTH)
    }

    // =========================================================================
    // 3. ChatDao CRUD & Query Operations
    // =========================================================================

    @Test
    fun testChatDaoInsertAndRetrieve() = runBlocking {
        val msg = ChatMessageEntity(
            messageId = "msg-uuid-101",
            missionId = "MISSION_ALPHA",
            senderId = "DEV_01",
            senderName = "Responder 1",
            text = "Water rising rapidly near entrance",
            timestamp = 1000L,
            status = ChatMessageEntity.STATUS_SENDING,
            isLocal = true,
            authorSequence = 1L
        )

        val rowId = chatDao.insert(msg)
        assertTrue(rowId > 0)

        val retrieved = chatDao.getMessage("msg-uuid-101")
        assertNotNull(retrieved)
        assertEquals("msg-uuid-101", retrieved?.messageId)
        assertEquals("Water rising rapidly near entrance", retrieved?.text)
        assertEquals(1, chatDao.exists("msg-uuid-101"))
        assertEquals(0, chatDao.exists("non-existent-id"))
    }

    @Test
    fun testChatDaoLinkedMessagesFlowForSOSIncident() = runBlocking {
        val sosEventId = "sos-event-888"

        // Insert broadcast message (not linked)
        chatDao.insert(ChatMessageEntity(
            messageId = "bcast-1",
            missionId = "MISSION_ALPHA",
            senderId = "DEV_01",
            senderName = "Responder 1",
            text = "General broadcast update",
            timestamp = 1000L,
            status = ChatMessageEntity.STATUS_SENT_TO_PEERS,
            linkedEventId = null
        ))

        // Insert SOS incident thread message
        chatDao.insert(ChatMessageEntity(
            messageId = "thread-1",
            missionId = "MISSION_ALPHA",
            senderId = "DEV_02",
            senderName = "Responder 2",
            text = "Three survivors on the second floor",
            timestamp = 2000L,
            status = ChatMessageEntity.STATUS_SENT_TO_PEERS,
            linkedEventId = sosEventId
        ))

        // Insert another SOS incident thread message
        chatDao.insert(ChatMessageEntity(
            messageId = "thread-2",
            missionId = "MISSION_ALPHA",
            senderId = "DEV_01",
            senderName = "Responder 1",
            text = "Deploying ladder from west entrance",
            timestamp = 3000L,
            status = ChatMessageEntity.STATUS_SENDING,
            linkedEventId = sosEventId
        ))

        val linkedMessages = chatDao.getLinkedMessagesFlow("MISSION_ALPHA", sosEventId).first()
        assertEquals("Linked messages flow must only contain messages for the given eventId", 2, linkedMessages.size)
        assertEquals("thread-1", linkedMessages[0].messageId)
        assertEquals("thread-2", linkedMessages[1].messageId)

        val allMessages = chatDao.getMessagesFlow("MISSION_ALPHA").first()
        assertEquals("All messages flow must contain all 3 messages", 3, allMessages.size)
    }

    @Test
    fun testChatDaoAckCountUpdate() = runBlocking {
        val msg = ChatMessageEntity(
            messageId = "msg-ack-test",
            missionId = "MISSION_ALPHA",
            senderId = "DEV_01",
            senderName = "Responder 1",
            text = "Team at checkpoint B",
            timestamp = 1000L,
            status = ChatMessageEntity.STATUS_SENDING,
            ackPeerCount = 0
        )
        chatDao.insert(msg)

        chatDao.updateAckCount("msg-ack-test", 2, ChatMessageEntity.STATUS_SENT_TO_PEERS)
        val updated = chatDao.getMessage("msg-ack-test")
        assertEquals(2, updated?.ackPeerCount)
        assertEquals(ChatMessageEntity.STATUS_SENT_TO_PEERS, updated?.status)
    }

    @Test
    fun testChatDaoSequenceTracking() = runBlocking {
        chatDao.insert(ChatMessageEntity(
            messageId = "seq-1",
            missionId = "MISSION_ALPHA",
            senderId = "DEVICE_X",
            senderName = "X",
            text = "m1",
            timestamp = 100L,
            status = "SENT_TO_PEERS",
            authorSequence = 1L
        ))
        chatDao.insert(ChatMessageEntity(
            messageId = "seq-2",
            missionId = "MISSION_ALPHA",
            senderId = "DEVICE_X",
            senderName = "X",
            text = "m2",
            timestamp = 200L,
            status = "SENT_TO_PEERS",
            authorSequence = 2L
        ))

        val latestSeq = chatDao.getLatestSequence("DEVICE_X")
        assertEquals(2L, latestSeq)

        val afterSeq1 = chatDao.getMessagesAfterSequence("DEVICE_X", 1L)
        assertEquals(1, afterSeq1.size)
        assertEquals("seq-2", afterSeq1[0].messageId)
    }

    @Test
    fun testChatDaoPruneOldMessages() = runBlocking {
        for (i in 1..10) {
            chatDao.insert(ChatMessageEntity(
                messageId = "prune-$i",
                missionId = "MISSION_ALPHA",
                senderId = "DEV_01",
                senderName = "R1",
                text = "msg $i",
                timestamp = i * 1000L,
                status = "RECEIVED"
            ))
        }

        assertEquals(10, chatDao.count("MISSION_ALPHA"))
        val pruned = chatDao.pruneOldMessages("MISSION_ALPHA", keepCount = 3)
        assertEquals(7, pruned)
        assertEquals(3, chatDao.count("MISSION_ALPHA"))
    }

    // =========================================================================
    // Fake ChatDao Implementation for Unit Tests
    // =========================================================================

    private class FakeTestChatDao : ChatDao {
        private val messages = ConcurrentHashMap<String, ChatMessageEntity>()
        private var idCounter = 1L

        override suspend fun insert(message: ChatMessageEntity): Long {
            val id = if (message.id == 0L) idCounter++ else message.id
            val stored = message.copy(id = id)
            messages[message.messageId] = stored
            return id
        }

        override suspend fun insertIfAbsent(message: ChatMessageEntity): Long {
            if (messages.containsKey(message.messageId)) return -1L
            return insert(message)
        }

        override fun getMessagesFlow(missionId: String) =
            flowOf(messages.values.filter { it.missionId == missionId }.sortedBy { it.timestamp })

        override fun getLinkedMessagesFlow(missionId: String, eventId: String) =
            flowOf(messages.values.filter { it.missionId == missionId && it.linkedEventId == eventId }.sortedBy { it.timestamp })

        override suspend fun getMessage(messageId: String): ChatMessageEntity? = messages[messageId]

        override suspend fun getRecentMessages(missionId: String, limit: Int): List<ChatMessageEntity> =
            messages.values.filter { it.missionId == missionId }.sortedByDescending { it.timestamp }.take(limit)

        override suspend fun getAllMessages(missionId: String): List<ChatMessageEntity> =
            messages.values.filter { it.missionId == missionId }.sortedBy { it.timestamp }

        override suspend fun updateStatus(messageId: String, status: String) {
            messages[messageId]?.let {
                messages[messageId] = it.copy(status = status)
            }
        }

        override suspend fun updateAckCount(messageId: String, ackPeerCount: Int, status: String) {
            messages[messageId]?.let {
                messages[messageId] = it.copy(ackPeerCount = ackPeerCount, status = status)
            }
        }

        override suspend fun recordPeerAck(messageId: String) {
            messages[messageId]?.let {
                messages[messageId] = it.copy(ackPeerCount = it.ackPeerCount + 1, status = "SENT_TO_PEERS")
            }
        }

        override suspend fun exists(messageId: String): Int = if (messages.containsKey(messageId)) 1 else 0

        override suspend fun count(missionId: String): Int = messages.values.count { it.missionId == missionId }

        override suspend fun getLatestSequence(senderId: String): Long? =
            messages.values.filter { it.senderId == senderId }.maxOfOrNull { it.authorSequence }

        override suspend fun getMessagesAfterSequence(senderId: String, afterSequence: Long): List<ChatMessageEntity> =
            messages.values.filter { it.senderId == senderId && it.authorSequence > afterSequence }.sortedBy { it.authorSequence }

        override suspend fun pruneOldMessages(missionId: String, keepCount: Int): Int {
            val toKeep = messages.values
                .filter { it.missionId == missionId }
                .sortedByDescending { it.timestamp }
                .take(keepCount)
                .map { it.messageId }
                .toSet()

            val toRemove = messages.values
                .filter { it.missionId == missionId && it.messageId !in toKeep }
                .map { it.messageId }

            toRemove.forEach { messages.remove(it) }
            return toRemove.size
        }
    }
}
