package com.rescuemesh.ai.data

import com.rescuemesh.ai.data.entity.MissionEntity
import com.rescuemesh.ai.fakes.FakeMissionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical stress harness challenging [FakeMissionDao] under concurrent multi-node conditions:
 * 1. Strict single-active-mission invariant under concurrent reads and writes
 * 2. Reader isolation during active mission switching
 * 3. Handling of non-existent mission switching
 */
class MissionLifecycleStressTest {

    @Test
    fun testConcurrentReadersObserveStrictSingleActiveMission() = runBlocking(Dispatchers.Default) {
        val dao = FakeMissionDao()
        val numMissions = 5
        for (i in 0 until numMissions) {
            dao.insertMission(
                MissionEntity(
                    missionId = "m-$i",
                    name = "Sector $i",
                    pairingCode = "CODE-$i",
                    createdAt = 1000L + i,
                    isActive = (i == 0)
                )
            )
        }

        val iterations = 500
        val zeroActiveCount = AtomicInteger(0)
        val multipleActiveCount = AtomicInteger(0)
        val nullActiveCount = AtomicInteger(0)

        // Writers continuously switch active mission
        val writers = (0 until 4).map { writerId ->
            launch {
                for (s in 0 until iterations) {
                    val targetId = "m-${(writerId + s) % numMissions}"
                    dao.setActiveMission(targetId)
                }
            }
        }

        // Readers continuously query active mission and all missions
        val readers = (0 until 4).map {
            launch {
                for (s in 0 until iterations) {
                    val active = dao.getActiveMission()
                    if (active == null) {
                        nullActiveCount.incrementAndGet()
                    }
                    val all = dao.getAllMissions()
                    val count = all.count { it.isActive }
                    if (count == 0) {
                        zeroActiveCount.incrementAndGet()
                    } else if (count > 1) {
                        multipleActiveCount.incrementAndGet()
                    }
                }
            }
        }

        (writers + readers).joinAll()

        println("Stress test results: nullActive=${nullActiveCount.get()}, zeroActive=${zeroActiveCount.get()}, multipleActive=${multipleActiveCount.get()}")

        assertEquals("getActiveMission() should never return null while an active mission exists", 0, nullActiveCount.get())
        assertEquals("getAllMissions() should never observe zero active missions", 0, zeroActiveCount.get())
        assertEquals("getAllMissions() should never observe multiple active missions", 0, multipleActiveCount.get())
    }

    @Test
    fun testSetActiveMissionWithNonExistentIdDeactivatesAll() = runBlocking {
        val dao = FakeMissionDao()
        dao.insertMission(MissionEntity("m-1", "Sector 1", "S1", isActive = true))
        dao.insertMission(MissionEntity("m-2", "Sector 2", "S2", isActive = false))

        assertNotNull(dao.getActiveMission())

        // Switching to non-existent ID should deactivate all existing missions
        dao.setActiveMission("non-existent-id")

        assertNull("No mission should be active", dao.getActiveMission())
        val all = dao.getAllMissions()
        assertEquals(0, all.count { it.isActive })
    }
}
