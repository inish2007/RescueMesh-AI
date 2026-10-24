package com.rescuemesh.ai.intelligence

import com.rescuemesh.ai.data.dao.ChatDao
import com.rescuemesh.ai.data.dao.MapDao
import com.rescuemesh.ai.data.dao.ObservationDao
import com.rescuemesh.ai.data.dao.OutboxDao
import com.rescuemesh.ai.data.entity.ChatMessageEntity
import com.rescuemesh.ai.data.entity.ObservationEntity

/**
 * Value-of-Information (VoI) priority scheduler.
 *
 * Scores pending observations and emergency chat messages for transmission priority
 * using a weighted feature vector:
 *   P = w_u*U + w_n*N + w_g*G + w_r*R - w_c*C - w_s*S
 *
 * Where:
 *   U = Urgency (how safety-critical is this observation?)
 *   N = Novelty (is this the first report on this topic?)
 *   G = Information Gain (does this resolve unknowns or conflicts on the map?)
 *   R = Relevance (does this affect key areas?)
 *   C = Cost (how large is the message?)
 *   S = Staleness (how old is the observation?)
 *
 * SAFETY OVERRIDE: Hazard and blocked-route reports have a minimum priority floor
 * (P >= 0.80f) that cannot be suppressed by the scoring model.
 *
 * CHAT CEILING: Emergency chat messages are strictly capped at P <= 0.60f
 * (general chat ~0.30f, SOS incident thread ~0.50f - 0.60f).
 * Emergency SOS and Hazards (P >= 0.80f) strictly preempt chat queues.
 *
 * These weights are development parameters, NOT research findings.
 * They must be calibrated on real captured events.
 */
class PriorityScheduler(
    private val observationDao: ObservationDao,
    private val mapDao: MapDao,
    private val outboxDao: OutboxDao,
    private val chatDao: ChatDao? = null
) {
    companion object {
        // Default weights — tunable
        private const val W_URGENCY = 0.30f
        private const val W_NOVELTY = 0.25f
        private const val W_INFO_GAIN = 0.25f
        private const val W_RELEVANCE = 0.10f
        private const val W_COST = 0.05f
        private const val W_STALENESS = 0.05f

        // Safety floor: hazards/blocks can never score below this
        const val SAFETY_PRIORITY_FLOOR = 0.80f

        // Hard safety ceiling: emergency chat can NEVER exceed 0.60f
        // Guarantees Emergency SOS (P >= 0.80f) strictly preempts chat traffic
        const val CHAT_PRIORITY_CEILING = 0.60f
        const val CHAT_INCIDENT_THREAD_BASE_URGENCY = 0.55f
        const val CHAT_GENERAL_BASE_URGENCY = 0.20f

        // Max age before an observation starts losing freshness (5 minutes)
        private const val MAX_FRESHNESS_AGE_MS = 5 * 60 * 1000L
    }

    /**
     * Score a single observation for transmission priority.
     * Returns a value in [0, 1] where higher = transmit first.
     */
    fun scoreObservation(observation: ObservationEntity): Float {
        val breakdown = scoreBreakdown(observation)
        return breakdown["finalScore"] ?: 0f
    }

    /**
     * Score a chat message for transmission priority.
     * Guaranteed to NEVER exceed CHAT_PRIORITY_CEILING (0.60f), ensuring
     * Emergency SOS and Hazard reports (P >= 0.80f) strictly preempt chat traffic.
     */
    fun scoreChatMessage(chat: ChatMessageEntity): Float {
        val breakdown = scoreChatMessageBreakdown(chat)
        return breakdown["finalScore"] ?: 0.30f
    }

    /**
     * Detailed score breakdown for an emergency chat message.
     */
    fun scoreChatMessageBreakdown(chat: ChatMessageEntity): Map<String, Float> {
        val isIncidentThread = !chat.linkedEventId.isNullOrBlank()
        val urgency = if (isIncidentThread) CHAT_INCIDENT_THREAD_BASE_URGENCY else CHAT_GENERAL_BASE_URGENCY
        val novelty = if (chat.isLocal) 0.60f else 0.40f
        val infoGain = if (isIncidentThread) 0.50f else 0.20f
        val relevance = if (isIncidentThread) 0.80f else 0.40f

        val textLen = chat.text.length.coerceIn(0, 500)
        val cost = (textLen.toFloat() / 500f) * 0.20f

        val ageMs = System.currentTimeMillis() - chat.timestamp
        val staleness = (ageMs.toFloat() / MAX_FRESHNESS_AGE_MS).coerceIn(0f, 1f)

        var score = (W_URGENCY * urgency) +
                    (W_NOVELTY * novelty) +
                    (W_INFO_GAIN * infoGain) +
                    (W_RELEVANCE * relevance) -
                    (W_COST * cost) -
                    (W_STALENESS * staleness)

        // Strict hard ceiling: chat can never exceed 0.60f and never reaches safety floor (0.80f)
        val finalScore = score.coerceIn(0.10f, CHAT_PRIORITY_CEILING)

        return mapOf(
            "urgency" to urgency,
            "novelty" to novelty,
            "informationGain" to infoGain,
            "relevance" to relevance,
            "cost" to cost,
            "staleness" to staleness,
            "rawScore" to score,
            "safetyCritical" to 0f,
            "finalScore" to finalScore
        )
    }

    /**
     * Get the detailed score breakdown for logging and research evaluation.
     */
    fun scoreBreakdown(observation: ObservationEntity): Map<String, Float> {
        val urgency = computeUrgency(observation)
        val novelty = computeNovelty(observation)
        val infoGain = computeInformationGain(observation)
        val relevance = computeRelevance(observation)
        val cost = computeCost(observation)
        val staleness = computeStaleness(observation)

        var score = (W_URGENCY * urgency) +
                    (W_NOVELTY * novelty) +
                    (W_INFO_GAIN * infoGain) +
                    (W_RELEVANCE * relevance) -
                    (W_COST * cost) -
                    (W_STALENESS * staleness)

        // Clamp to [0, 1]
        score = score.coerceIn(0f, 1f)

        // SAFETY OVERRIDE: critical reports get a minimum floor
        val isSafetyCritical = urgency >= 0.9f
        val finalScore = if (isSafetyCritical) maxOf(score, SAFETY_PRIORITY_FLOOR) else score

        return mapOf(
            "urgency" to urgency,
            "novelty" to novelty,
            "informationGain" to infoGain,
            "relevance" to relevance,
            "cost" to cost,
            "staleness" to staleness,
            "rawScore" to score,
            "safetyCritical" to if (isSafetyCritical) 1f else 0f,
            "finalScore" to finalScore
        )
    }

    /**
     * Select events from the outbox within a byte budget, ordered by priority.
     */
    suspend fun selectWithinBudget(maxBytes: Int = Int.MAX_VALUE): List<Pair<String, Float>> {
        val pending = outboxDao.getPendingByPriority()
        val scoredList = mutableListOf<Triple<String, Float, Int>>()

        for (entry in pending) {
            val obs = observationDao.getById(entry.eventId)
            if (obs != null) {
                val priority = scoreObservation(obs)
                val estimatedBytes = (obs.title.length + obs.description.length + 200) * 2
                scoredList.add(Triple(entry.eventId, priority, estimatedBytes))
            } else {
                val chat = chatDao?.getMessage(entry.eventId)
                if (chat != null) {
                    val priority = scoreChatMessage(chat)
                    val estimatedBytes = (chat.text.length + 220) * 2
                    scoredList.add(Triple(entry.eventId, priority, estimatedBytes))
                }
            }
        }

        // Must sort by priority descending BEFORE budget cutoff
        val sortedByPriority = scoredList.sortedByDescending { it.second }
        val result = mutableListOf<Pair<String, Float>>()
        var totalBytes = 0

        for ((eventId, priority, estimatedBytes) in sortedByPriority) {
            if (totalBytes + estimatedBytes > maxBytes) break
            result.add(eventId to priority)
            totalBytes += estimatedBytes
        }

        return result
    }

    // --- Feature Computations ---

    private fun computeUrgency(obs: ObservationEntity): Float {
        val eventTypeUpper = obs.eventType.uppercase()
        val statusUpper = obs.reportedStatus?.uppercase()

        return when {
            eventTypeUpper == "SOS" -> 1.0f
            eventTypeUpper == "HAZARD" -> 1.0f
            statusUpper in listOf("BLOCKED", "REPORTED_BLOCKED", "VERIFIED_BLOCKED") -> 0.95f
            eventTypeUpper == "ROUTE_STATUS" && statusUpper in listOf("OPEN", "REPORTED_OPEN", "VERIFIED_OPEN") -> 0.7f
            eventTypeUpper == "ROUTE_STATUS" -> 0.6f
            eventTypeUpper == "PHOTO" -> 0.4f
            eventTypeUpper == "NOTE" -> 0.3f
            else -> 0.2f
        }
    }

    private fun computeNovelty(obs: ObservationEntity): Float {
        // If this observation is about a map edge, check if it's the first report
        if (obs.mapEdgeId != null) {
            // Heuristic: local observations about edges are likely novel
            // In a full implementation, we'd check the map state
            return if (obs.isLocal) 0.9f else 0.5f
        }
        // General observations are moderately novel
        return 0.6f
    }

    private fun computeInformationGain(obs: ObservationEntity): Float {
        val eventTypeUpper = obs.eventType.uppercase()
        return when {
            eventTypeUpper == "SOS" -> 1.0f
            // Reports that could resolve conflicts are high-value
            obs.verificationStatus == "VERIFIED" -> 1.0f
            // Route status changes provide high information gain
            obs.reportedStatus == "BLOCKED" -> 0.9f
            obs.reportedStatus == "OPEN" -> 0.7f
            // General observations
            eventTypeUpper == "HAZARD" -> 0.8f
            else -> 0.3f
        }
    }

    private fun computeRelevance(obs: ObservationEntity): Float {
        // All observations in the current mission are relevant
        // In a full implementation, this would consider assigned zones/routes
        return if (obs.mapEdgeId != null || obs.mapNodeId != null) 0.8f else 0.5f
    }

    private fun computeCost(obs: ObservationEntity): Float {
        // Normalize: larger messages cost more
        val estimatedSize = (obs.title.length + obs.description.length + 200)
        return (estimatedSize.toFloat() / 2000f).coerceIn(0f, 1f)
    }

    private fun computeStaleness(obs: ObservationEntity): Float {
        val ageMs = System.currentTimeMillis() - obs.observedAtDevice
        return (ageMs.toFloat() / MAX_FRESHNESS_AGE_MS).coerceIn(0f, 1f)
    }
}
