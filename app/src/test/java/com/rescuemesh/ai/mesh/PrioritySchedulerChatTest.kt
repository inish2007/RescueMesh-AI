package com.rescuemesh.ai.mesh

import com.rescuemesh.ai.data.dao.ChatDao
import com.rescuemesh.ai.data.dao.MapDao
import com.rescuemesh.ai.data.dao.ObservationDao
import com.rescuemesh.ai.data.dao.OutboxDao
import com.rescuemesh.ai.data.entity.ChatMessageEntity
import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.data.entity.OutboxEntity
import com.rescuemesh.ai.fakes.FakeMapDao
import com.rescuemesh.ai.fakes.FakeObservationDao
import com.rescuemesh.ai.fakes.FakeOutboxDao
import com.rescuemesh.ai.intelligence.PriorityScheduler
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit Test Suite for PriorityScheduler Chat Scoring & SOS Preemption:
 * Verifies that chat messages are capped at P <= 0.60f and Emergency SOS (P >= 0.80f)
 * strictly preempts chat in the transmission queue.
 */
class PrioritySchedulerChatTest {

    private lateinit var observationDao: FakeObservationDao
    private lateinit var mapDao: FakeMapDao
    private lateinit var outboxDao: FakeOutboxDao
    private lateinit var chatDao: FakeSchedulerChatDao
    private lateinit var scheduler: PriorityScheduler

    @Before
    fun setUp() {
        observationDao = FakeObservationDao()
        mapDao = FakeMapDao()
        outboxDao = FakeOutboxDao()
        chatDao = FakeSchedulerChatDao()
        scheduler = PriorityScheduler(
            observationDao = observationDao,
            mapDao = mapDao,
            outboxDao = outboxDao,
            chatDao = chatDao
        )
    }

    @Test
    fun testGeneralChatScoresBelowCeiling() {
        val generalChat = ChatMessageEntity(
            messageId = "chat-gen-1",
            missionId = "MISSION_ALPHA",
            senderId = "DEV_01",
            senderName = "Operator",
            text = "Checking in from sector 2.",
            timestamp = System.currentTimeMillis(),
            status = ChatMessageEntity.STATUS_SENDING,
            linkedEventId = null
        )

        val score = scheduler.scoreChatMessage(generalChat)
        assertTrue("General chat score must be <= 0.60f (ceiling)", score <= PriorityScheduler.CHAT_PRIORITY_CEILING)
        assertTrue("General chat score should be around ~0.30f, got $score", score in 0.20f..0.40f)
    }

    @Test
    fun testIncidentThreadChatScoresHigherThanGeneralChatButBelowCeiling() {
        val now = System.currentTimeMillis()
        val generalChat = ChatMessageEntity(
            messageId = "chat-gen-2",
            missionId = "MISSION_ALPHA",
            senderId = "DEV_01",
            senderName = "Operator",
            text = "Routine perimeter check.",
            timestamp = now,
            status = ChatMessageEntity.STATUS_SENDING,
            linkedEventId = null
        )

        val incidentChat = ChatMessageEntity(
            messageId = "chat-sos-1",
            missionId = "MISSION_ALPHA",
            senderId = "DEV_02",
            senderName = "Medic",
            text = "Two people evacuated, structural crack expanding.",
            timestamp = now,
            status = ChatMessageEntity.STATUS_SENDING,
            linkedEventId = "sos-incident-123"
        )

        val generalScore = scheduler.scoreChatMessage(generalChat)
        val incidentScore = scheduler.scoreChatMessage(incidentChat)

        assertTrue("Incident thread score ($incidentScore) must be strictly higher than general chat ($generalScore)",
            incidentScore > generalScore)
        assertTrue("Incident thread score must still be <= 0.60f (ceiling), got $incidentScore",
            incidentScore <= PriorityScheduler.CHAT_PRIORITY_CEILING)
    }

    @Test
    fun testSOSObservationStrictlyPreemptsChatQueues() {
        val sosObs = ObservationEntity(
            eventId = "sos-urgent-999",
            missionId = "MISSION_ALPHA",
            authorDeviceId = "DEV_SOS",
            authorSequence = 1L,
            eventType = "SOS",
            title = "EMERGENCY: Trapped survivors",
            observedAtDevice = System.currentTimeMillis()
        )

        val incidentChat = ChatMessageEntity(
            messageId = "chat-sos-thread",
            missionId = "MISSION_ALPHA",
            senderId = "DEV_01",
            senderName = "Responder",
            text = "Critical information on stairwell.",
            timestamp = System.currentTimeMillis(),
            status = ChatMessageEntity.STATUS_SENDING,
            linkedEventId = "sos-urgent-999"
        )

        val sosScore = scheduler.scoreObservation(sosObs)
        val chatScore = scheduler.scoreChatMessage(incidentChat)

        assertTrue("SOS observation score must meet or exceed safety floor 0.80f, got $sosScore",
            sosScore >= PriorityScheduler.SAFETY_PRIORITY_FLOOR)
        assertTrue("Chat score must be <= 0.60f, got $chatScore",
            chatScore <= PriorityScheduler.CHAT_PRIORITY_CEILING)

        val gap = sosScore - chatScore
        assertTrue("Strict preemption gap between SOS and Chat must be >= 0.20f, was $gap",
            gap >= 0.20f)
    }

    @Test
    fun testHazardObservationPreemptsChat() {
        val hazardObs = ObservationEntity(
            eventId = "hazard-001",
            missionId = "MISSION_ALPHA",
            authorDeviceId = "DEV_01",
            authorSequence = 2L,
            eventType = "HAZARD",
            title = "Live electrical wires across path",
            observedAtDevice = System.currentTimeMillis()
        )

        val chat = ChatMessageEntity(
            messageId = "chat-001",
            missionId = "MISSION_ALPHA",
            senderId = "DEV_02",
            senderName = "Ops",
            text = "Need status report on generator.",
            timestamp = System.currentTimeMillis(),
            status = ChatMessageEntity.STATUS_SENDING
        )

        val hazardScore = scheduler.scoreObservation(hazardObs)
        val chatScore = scheduler.scoreChatMessage(chat)

        assertTrue("Hazard observation must score >= 0.80f, got $hazardScore",
            hazardScore >= PriorityScheduler.SAFETY_PRIORITY_FLOOR)
        assertTrue("Hazard must preempt chat", hazardScore > chatScore)
    }

    @Test
    fun testSelectWithinBudgetSortsSOSAheadOfChat() = runBlocking {
        val sosObs = ObservationEntity(
            eventId = "obs-sos-1",
            missionId = "MISSION_ALPHA",
            authorDeviceId = "DEV_01",
            authorSequence = 1L,
            eventType = "SOS",
            title = "SOS",
            observedAtDevice = System.currentTimeMillis()
        )
        observationDao.insert(sosObs)

        val chatMsg = ChatMessageEntity(
            messageId = "chat-msg-1",
            missionId = "MISSION_ALPHA",
            senderId = "DEV_02",
            senderName = "Team 2",
            text = "En route to sector 3.",
            timestamp = System.currentTimeMillis(),
            status = ChatMessageEntity.STATUS_SENDING,
            linkedEventId = "obs-sos-1"
        )
        chatDao.insert(chatMsg)

        // Insert outbox entries in reverse order (chat first, SOS second)
        val chatPriority = scheduler.scoreChatMessage(chatMsg)
        val sosPriority = scheduler.scoreObservation(sosObs)

        outboxDao.insert(OutboxEntity(
            eventId = "chat-msg-1",
            priority = chatPriority,
            status = "PENDING"
        ))
        outboxDao.insert(OutboxEntity(
            eventId = "obs-sos-1",
            priority = sosPriority,
            status = "PENDING"
        ))

        val selected = scheduler.selectWithinBudget(maxBytes = 50000)
        assertEquals(2, selected.size)
        assertEquals("SOS observation must be selected first regardless of insertion order",
            "obs-sos-1", selected[0].first)
        assertEquals("Chat message must be selected second",
            "chat-msg-1", selected[1].first)
    }

    private class FakeSchedulerChatDao : ChatDao {
        private val messages = ConcurrentHashMap<String, ChatMessageEntity>()

        override suspend fun insert(message: ChatMessageEntity): Long {
            messages[message.messageId] = message
            return 1L
        }
        override suspend fun insertIfAbsent(message: ChatMessageEntity): Long = insert(message)
        override fun getMessagesFlow(missionId: String) = flowOf(messages.values.toList())
        override fun getLinkedMessagesFlow(missionId: String, eventId: String) = flowOf(messages.values.filter { it.linkedEventId == eventId })
        override suspend fun getMessage(messageId: String): ChatMessageEntity? = messages[messageId]
        override suspend fun getRecentMessages(missionId: String, limit: Int): List<ChatMessageEntity> = messages.values.take(limit)
        override suspend fun getAllMessages(missionId: String): List<ChatMessageEntity> = messages.values.toList()
        override suspend fun updateStatus(messageId: String, status: String) {}
        override suspend fun updateAckCount(messageId: String, ackPeerCount: Int, status: String) {}
        override suspend fun recordPeerAck(messageId: String) {}
        override suspend fun exists(messageId: String): Int = if (messages.containsKey(messageId)) 1 else 0
        override suspend fun count(missionId: String): Int = messages.size
        override suspend fun getLatestSequence(senderId: String): Long? = null
        override suspend fun getMessagesAfterSequence(senderId: String, afterSequence: Long): List<ChatMessageEntity> = emptyList()
        override suspend fun pruneOldMessages(missionId: String, keepCount: Int): Int = 0
    }
}
