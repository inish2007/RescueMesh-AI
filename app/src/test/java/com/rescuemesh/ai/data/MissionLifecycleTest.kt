package com.rescuemesh.ai.data

import com.rescuemesh.ai.data.entity.MissionEntity
import com.rescuemesh.ai.fakes.FakeMissionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Mission Lifecycle, DAO contracts, pairing code lookup,
 * and active mission switching under Milestone 2.
 */
class MissionLifecycleTest {

    private lateinit var missionDao: FakeMissionDao

    @Before
    fun setUp() {
        missionDao = FakeMissionDao()
    }

    @Test
    fun testDefaultMissionValues() {
        val before = System.currentTimeMillis()
        val mission = MissionEntity(
            missionId = "m-default",
            name = "Disaster Alpha",
            pairingCode = "RC-01"
        )
        val after = System.currentTimeMillis()

        assertEquals("m-default", mission.missionId)
        assertEquals("Disaster Alpha", mission.name)
        assertEquals("RC-01", mission.pairingCode)
        assertEquals("", mission.description)
        assertTrue("createdAt should be near current time", mission.createdAt in before..after)
        assertTrue("isActive should default to true", mission.isActive)
    }

    @Test
    fun testInsertAndRetrieveMission() = runBlocking {
        val mission = MissionEntity(
            missionId = "m-alpha",
            name = "Flood Sector 1",
            pairingCode = "FS-01",
            description = "Northern flood evacuation sector",
            createdAt = 1000L,
            isActive = true
        )
        missionDao.insertMission(mission)

        val retrieved = missionDao.getMissionById("m-alpha")
        assertNotNull(retrieved)
        assertEquals("m-alpha", retrieved?.missionId)
        assertEquals("Flood Sector 1", retrieved?.name)
        assertEquals("FS-01", retrieved?.pairingCode)
        assertEquals("Northern flood evacuation sector", retrieved?.description)
        assertEquals(1000L, retrieved?.createdAt)
        assertTrue(retrieved?.isActive == true)

        val all = missionDao.getAllMissions()
        assertEquals(1, all.size)
        assertEquals("m-alpha", all[0].missionId)
    }

    @Test
    fun testObserveActiveMission() = runBlocking {
        // Initially empty
        val initialActive = missionDao.observeActiveMission().first()
        assertNull(initialActive)

        // Insert first active mission
        val m1 = MissionEntity("m-1", "Sector Alpha", "SA-01", isActive = true)
        missionDao.insertMission(m1)
        val active1 = missionDao.observeActiveMission().first()
        assertNotNull(active1)
        assertEquals("m-1", active1?.missionId)

        // Insert inactive mission
        val m2 = MissionEntity("m-2", "Sector Bravo", "SB-02", isActive = false)
        missionDao.insertMission(m2)
        val activeAfterM2 = missionDao.observeActiveMission().first()
        assertEquals("m-1", activeAfterM2?.missionId)

        // Switch active mission to m-2
        missionDao.setActiveMission("m-2")
        val activeAfterSwitch = missionDao.observeActiveMission().first()
        assertEquals("m-2", activeAfterSwitch?.missionId)
    }

    @Test
    fun testSetActiveMissionSwitchesActiveFlagAtomically() = runBlocking {
        val m1 = MissionEntity("m-1", "Sector A", "SA-01", createdAt = 100L, isActive = true)
        val m2 = MissionEntity("m-2", "Sector B", "SB-02", createdAt = 200L, isActive = false)
        val m3 = MissionEntity("m-3", "Sector C", "SC-03", createdAt = 300L, isActive = false)

        missionDao.insertMission(m1)
        missionDao.insertMission(m2)
        missionDao.insertMission(m3)

        assertEquals("m-1", missionDao.getActiveMission()?.missionId)

        // Switch to m-3
        missionDao.setActiveMission("m-3")

        val active = missionDao.getActiveMission()
        assertEquals("m-3", active?.missionId)
        assertTrue(active?.isActive == true)

        // Verify other missions are now inactive
        val all = missionDao.getAllMissions()
        val m1Updated = all.first { it.missionId == "m-1" }
        val m2Updated = all.first { it.missionId == "m-2" }
        val m3Updated = all.first { it.missionId == "m-3" }

        assertFalse(m1Updated.isActive)
        assertFalse(m2Updated.isActive)
        assertTrue(m3Updated.isActive)
    }

    @Test
    fun testLookupByPairingCode() = runBlocking {
        val m1 = MissionEntity("m-1", "Alpha Command", "RC-01")
        val m2 = MissionEntity("m-2", "Bravo Command", "RC-02")
        missionDao.insertMission(m1)
        missionDao.insertMission(m2)

        // Exact match
        val foundExact = missionDao.getMissionByPairingCode("RC-01")
        assertNotNull(foundExact)
        assertEquals("m-1", foundExact?.missionId)

        // Case insensitivity
        val foundLower = missionDao.getMissionByPairingCode("rc-01")
        assertNotNull(foundLower)
        assertEquals("m-1", foundLower?.missionId)

        // Whitespace trimmed
        val foundTrimmed = missionDao.getMissionByPairingCode("  RC-02  ")
        assertNotNull(foundTrimmed)
        assertEquals("m-2", foundTrimmed?.missionId)

        // Non-existent pairing code
        val notFound = missionDao.getMissionByPairingCode("UNKNOWN-99")
        assertNull(notFound)
    }

    @Test
    fun testGetAllMissionsSortedByCreatedAtDescending() = runBlocking {
        val mOld = MissionEntity("m-old", "Old Mission", "OM-01", createdAt = 1000L)
        val mNew = MissionEntity("m-new", "New Mission", "NM-02", createdAt = 3000L)
        val mMid = MissionEntity("m-mid", "Mid Mission", "MM-03", createdAt = 2000L)

        missionDao.insertMission(mOld)
        missionDao.insertMission(mNew)
        missionDao.insertMission(mMid)

        val all = missionDao.getAllMissions()
        assertEquals(3, all.size)
        assertEquals("m-new", all[0].missionId)
        assertEquals("m-mid", all[1].missionId)
        assertEquals("m-old", all[2].missionId)
    }

    @Test
    fun testReplaceMissionOnConflict() = runBlocking {
        val original = MissionEntity("m-conflict", "Original Title", "OC-01")
        missionDao.insertMission(original)

        val updated = MissionEntity("m-conflict", "Revised Title", "RC-02", description = "Updated notes")
        missionDao.insertMission(updated)

        val retrieved = missionDao.getMissionById("m-conflict")
        assertNotNull(retrieved)
        assertEquals("Revised Title", retrieved?.name)
        assertEquals("RC-02", retrieved?.pairingCode)
        assertEquals("Updated notes", retrieved?.description)

        val all = missionDao.getAllMissions()
        assertEquals(1, all.size)
    }

    @Test
    fun testConcurrentMissionSwitching() = runBlocking(Dispatchers.Default) {
        val numMissions = 10
        for (i in 0 until numMissions) {
            missionDao.insertMission(
                MissionEntity(
                    missionId = "m-$i",
                    name = "Sector $i",
                    pairingCode = "CODE-$i",
                    isActive = (i == 0)
                )
            )
        }

        val threads = 10
        val switchesPerThread = 30

        val jobs = (0 until threads).map { threadIdx ->
            launch {
                for (s in 0 until switchesPerThread) {
                    val targetId = "m-${(threadIdx + s) % numMissions}"
                    missionDao.setActiveMission(targetId)
                    val active = missionDao.getActiveMission()
                    assertNotNull(active)
                    val byCode = missionDao.getMissionByPairingCode("code-${(threadIdx + s) % numMissions}")
                    assertNotNull(byCode)
                }
            }
        }
        jobs.joinAll()

        // After all concurrent switches finish, exactly one mission should be active
        val all = missionDao.getAllMissions()
        val activeCount = all.count { it.isActive }
        assertEquals("Exactly one mission should be active", 1, activeCount)
    }
}
