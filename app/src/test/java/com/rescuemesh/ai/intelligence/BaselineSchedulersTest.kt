package com.rescuemesh.ai.intelligence

import com.rescuemesh.ai.data.entity.OutboxEntity
import org.junit.Assert.*
import org.junit.Test

class BaselineSchedulersTest {

    private fun createOutboxEntry(id: Long, eventId: String, createdAt: Long): OutboxEntity {
        return OutboxEntity(
            id = id,
            eventId = eventId,
            createdAt = createdAt
        )
    }

    @Test
    fun testFifoOrderingSortsByCreatedAtAscending() {
        val entry1 = createOutboxEntry(1L, "ev-1", 5000L)
        val entry2 = createOutboxEntry(2L, "ev-2", 1000L)
        val entry3 = createOutboxEntry(3L, "ev-3", 3000L)
        val entry4 = createOutboxEntry(4L, "ev-4", 2000L)

        val pending = listOf(entry1, entry2, entry3, entry4)
        val fifo = BaselineSchedulers.fifoOrder(pending)

        assertEquals(4, fifo.size)
        assertEquals(listOf("ev-2", "ev-4", "ev-3", "ev-1"), fifo.map { it.eventId })
        assertEquals(listOf(1000L, 2000L, 3000L, 5000L), fifo.map { it.createdAt })
    }

    @Test
    fun testFifoOrderWithEmptyAndSingleList() {
        assertTrue(BaselineSchedulers.fifoOrder(emptyList()).isEmpty())

        val single = listOf(createOutboxEntry(1L, "ev-single", 1000L))
        val result = BaselineSchedulers.fifoOrder(single)
        assertEquals(1, result.size)
        assertEquals("ev-single", result[0].eventId)
    }

    @Test
    fun testRandomSchedulerOrderingBehavior() {
        val pending = (1..25).map { i ->
            createOutboxEntry(i.toLong(), "ev-$i", (i * 100).toLong())
        }

        val randomOrdered = BaselineSchedulers.randomOrder(pending)

        // Preserves all elements and count
        assertEquals(pending.size, randomOrdered.size)
        assertEquals(pending.map { it.eventId }.toSet(), randomOrdered.map { it.eventId }.toSet())

        // Over multiple iterations, order should vary and not strictly equal input FIFO order
        var orderChangedAtLeastOnce = false
        for (trial in 1..20) {
            val shuffled = BaselineSchedulers.randomOrder(pending)
            if (shuffled.map { it.eventId } != pending.map { it.eventId }) {
                orderChangedAtLeastOnce = true
                break
            }
        }
        assertTrue("Random scheduler should alter ordering", orderChangedAtLeastOnce)
    }

    @Test
    fun testNewestFirstOrderingSortsByCreatedAtDescending() {
        val entry1 = createOutboxEntry(1L, "ev-1", 1000L)
        val entry2 = createOutboxEntry(2L, "ev-2", 4000L)
        val entry3 = createOutboxEntry(3L, "ev-3", 2500L)

        val pending = listOf(entry1, entry2, entry3)
        val newest = BaselineSchedulers.newestFirstOrder(pending)

        assertEquals(3, newest.size)
        assertEquals(listOf("ev-2", "ev-3", "ev-1"), newest.map { it.eventId })
        assertEquals(listOf(4000L, 2500L, 1000L), newest.map { it.createdAt })
    }

    @Test
    fun testCompareSchedulersHonorsMaxEventsAndSchedulerKeys() {
        val pending = (1..10).map { i ->
            createOutboxEntry(i.toLong(), "ev-$i", (i * 100).toLong())
        }
        // VoI order reversed from input
        val voiOrder = pending.reversed()

        val comparison = BaselineSchedulers.compareSchedulers(
            pending = pending,
            voiOrder = voiOrder,
            maxEvents = 3
        )

        assertTrue(comparison.containsKey("VOI"))
        assertTrue(comparison.containsKey("FIFO"))
        assertTrue(comparison.containsKey("RANDOM"))
        assertTrue(comparison.containsKey("NEWEST"))

        assertEquals(3, comparison["VOI"]!!.size)
        assertEquals(3, comparison["FIFO"]!!.size)
        assertEquals(3, comparison["RANDOM"]!!.size)
        assertEquals(3, comparison["NEWEST"]!!.size)

        // VOI should take top 3 from voiOrder
        assertEquals(listOf("ev-10", "ev-9", "ev-8"), comparison["VOI"])

        // FIFO should take oldest 3
        assertEquals(listOf("ev-1", "ev-2", "ev-3"), comparison["FIFO"])

        // NEWEST should take newest 3
        assertEquals(listOf("ev-10", "ev-9", "ev-8"), comparison["NEWEST"])

        // RANDOM elements should be from original set
        val allEventIds = pending.map { it.eventId }.toSet()
        assertTrue(comparison["RANDOM"]!!.all { it in allEventIds })
    }
}
