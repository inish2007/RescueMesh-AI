package com.rescuemesh.ai.intelligence

import com.rescuemesh.ai.data.entity.OutboxEntity

/**
 * Baseline scheduling policies for comparison with the VoI scheduler.
 *
 * These exist purely for research evaluation:
 * - Compare delivery times and map quality across schedulers
 * - Demonstrate that VoI scheduling improves information delivery
 */
object BaselineSchedulers {

    /**
     * FIFO: Send oldest events first.
     * The simplest possible policy — most conventional systems use this.
     */
    fun fifoOrder(pending: List<OutboxEntity>): List<OutboxEntity> {
        return pending.sortedBy { it.createdAt }
    }

    /**
     * Random: Select events in random order.
     * The weakest baseline — shows that any intelligent policy should beat random.
     */
    fun randomOrder(pending: List<OutboxEntity>): List<OutboxEntity> {
        return pending.shuffled()
    }

    /**
     * Newest-first: Send most recent events first.
     * A reasonable heuristic — newest observations may be most relevant.
     */
    fun newestFirstOrder(pending: List<OutboxEntity>): List<OutboxEntity> {
        return pending.sortedByDescending { it.createdAt }
    }

    /**
     * Simulate what each scheduler would send given a byte budget.
     * Used for offline comparison metrics.
     */
    fun compareSchedulers(
        pending: List<OutboxEntity>,
        voiOrder: List<OutboxEntity>,
        maxEvents: Int = 5
    ): Map<String, List<String>> {
        return mapOf(
            "VOI" to voiOrder.take(maxEvents).map { it.eventId },
            "FIFO" to fifoOrder(pending).take(maxEvents).map { it.eventId },
            "RANDOM" to randomOrder(pending).take(maxEvents).map { it.eventId },
            "NEWEST" to newestFirstOrder(pending).take(maxEvents).map { it.eventId }
        )
    }
}
