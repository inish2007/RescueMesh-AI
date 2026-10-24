package com.rescuemesh.ai.intelligence

import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.data.entity.OutboxEntity
import com.rescuemesh.ai.fakes.FakeMapDao
import com.rescuemesh.ai.fakes.FakeObservationDao
import com.rescuemesh.ai.fakes.FakeOutboxDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PrioritySchedulerTest {

    private lateinit var observationDao: FakeObservationDao
    private lateinit var mapDao: FakeMapDao
    private lateinit var outboxDao: FakeOutboxDao
    private lateinit var scheduler: PriorityScheduler

    @Before
    fun setUp() {
        observationDao = FakeObservationDao()
        mapDao = FakeMapDao()
        outboxDao = FakeOutboxDao()
        scheduler = PriorityScheduler(observationDao, mapDao, outboxDao)
    }

    private fun createObservation(
        eventId: String = "obs-1",
        eventType: String = "NOTE",
        title: String = "Test Observation",
        description: String = "Testing scheduler",
        reportedStatus: String? = null,
        verificationStatus: String = "UNVERIFIED",
        mapEdgeId: String? = null,
        mapNodeId: String? = null,
        isLocal: Boolean = true,
        observedAtDevice: Long = System.currentTimeMillis()
    ): ObservationEntity {
        return ObservationEntity(
            eventId = eventId,
            missionId = "mission-1",
            authorDeviceId = "device-1",
            authorSequence = 1L,
            eventType = eventType,
            title = title,
            description = description,
            reportedStatus = reportedStatus,
            verificationStatus = verificationStatus,
            mapEdgeId = mapEdgeId,
            mapNodeId = mapNodeId,
            isLocal = isLocal,
            observedAtDevice = observedAtDevice
        )
    }

    @Test
    fun testFeatureUrgencyComputation() {
        val sos = createObservation(eventType = "SOS")
        val hazard = createObservation(eventType = "HAZARD")
        val blocked = createObservation(eventType = "ROUTE_STATUS", reportedStatus = "BLOCKED")
        val openRoute = createObservation(eventType = "ROUTE_STATUS", reportedStatus = "OPEN")
        val routeStatusOther = createObservation(eventType = "ROUTE_STATUS", reportedStatus = null)
        val photo = createObservation(eventType = "PHOTO")
        val note = createObservation(eventType = "NOTE")
        val other = createObservation(eventType = "CUSTOM_SENSOR")

        assertEquals(1.0f, scheduler.scoreBreakdown(sos)["urgency"]!!, 1e-4f)
        assertEquals(1.0f, scheduler.scoreBreakdown(hazard)["urgency"]!!, 1e-4f)
        assertEquals(0.95f, scheduler.scoreBreakdown(blocked)["urgency"]!!, 1e-4f)
        assertEquals(0.7f, scheduler.scoreBreakdown(openRoute)["urgency"]!!, 1e-4f)
        assertEquals(0.6f, scheduler.scoreBreakdown(routeStatusOther)["urgency"]!!, 1e-4f)
        assertEquals(0.4f, scheduler.scoreBreakdown(photo)["urgency"]!!, 1e-4f)
        assertEquals(0.3f, scheduler.scoreBreakdown(note)["urgency"]!!, 1e-4f)
        assertEquals(0.2f, scheduler.scoreBreakdown(other)["urgency"]!!, 1e-4f)

        // Verify SOS triggers safety critical floor override (>= 0.80)
        val sosBreakdown = scheduler.scoreBreakdown(sos)
        assertEquals(1.0f, sosBreakdown["safetyCritical"]!!, 1e-4f)
        assertTrue("SOS score must be at least 0.80", sosBreakdown["finalScore"]!! >= 0.80f)
    }

    @Test
    fun testFeatureNoveltyComputation() {
        val localEdgeObs = createObservation(mapEdgeId = "edge-1", isLocal = true)
        val remoteEdgeObs = createObservation(mapEdgeId = "edge-1", isLocal = false)
        val nonEdgeObs = createObservation(mapEdgeId = null)

        assertEquals(0.9f, scheduler.scoreBreakdown(localEdgeObs)["novelty"]!!, 1e-4f)
        assertEquals(0.5f, scheduler.scoreBreakdown(remoteEdgeObs)["novelty"]!!, 1e-4f)
        assertEquals(0.6f, scheduler.scoreBreakdown(nonEdgeObs)["novelty"]!!, 1e-4f)
    }

    @Test
    fun testFeatureInformationGainComputation() {
        val verified = createObservation(verificationStatus = "VERIFIED")
        val blocked = createObservation(reportedStatus = "BLOCKED")
        val open = createObservation(reportedStatus = "OPEN")
        val hazard = createObservation(eventType = "HAZARD")
        val note = createObservation(eventType = "NOTE")

        assertEquals(1.0f, scheduler.scoreBreakdown(verified)["informationGain"]!!, 1e-4f)
        assertEquals(0.9f, scheduler.scoreBreakdown(blocked)["informationGain"]!!, 1e-4f)
        assertEquals(0.7f, scheduler.scoreBreakdown(open)["informationGain"]!!, 1e-4f)
        assertEquals(0.8f, scheduler.scoreBreakdown(hazard)["informationGain"]!!, 1e-4f)
        assertEquals(0.3f, scheduler.scoreBreakdown(note)["informationGain"]!!, 1e-4f)
    }

    @Test
    fun testFeatureRelevanceComputation() {
        val withEdge = createObservation(mapEdgeId = "edge-1", mapNodeId = null)
        val withNode = createObservation(mapEdgeId = null, mapNodeId = "node-1")
        val withNeither = createObservation(mapEdgeId = null, mapNodeId = null)

        assertEquals(0.8f, scheduler.scoreBreakdown(withEdge)["relevance"]!!, 1e-4f)
        assertEquals(0.8f, scheduler.scoreBreakdown(withNode)["relevance"]!!, 1e-4f)
        assertEquals(0.5f, scheduler.scoreBreakdown(withNeither)["relevance"]!!, 1e-4f)
    }

    @Test
    fun testFeatureCostAndStalenessComputation() {
        // Size = title.len + desc.len + 200 = 0 + 800 + 200 = 1000. Cost = 1000 / 2000 = 0.5f
        val mediumObs = createObservation(title = "", description = "A".repeat(800))
        assertEquals(0.5f, scheduler.scoreBreakdown(mediumObs)["cost"]!!, 1e-3f)

        // Max cost clamped to 1.0f
        val hugeObs = createObservation(title = "A".repeat(1000), description = "B".repeat(1500))
        assertEquals(1.0f, scheduler.scoreBreakdown(hugeObs)["cost"]!!, 1e-3f)

        // Fresh observation
        val freshObs = createObservation(observedAtDevice = System.currentTimeMillis())
        assertTrue(scheduler.scoreBreakdown(freshObs)["staleness"]!! < 0.05f)

        // 5+ minutes old (MAX_FRESHNESS_AGE_MS = 300,000 ms)
        val staleObs = createObservation(observedAtDevice = System.currentTimeMillis() - (10 * 60 * 1000L))
        assertEquals(1.0f, scheduler.scoreBreakdown(staleObs)["staleness"]!!, 1e-4f)
    }

    @Test
    fun testVoIScoringFormulaWeights() {
        // Verification of rawScore = w_u*U + w_n*N + w_g*G + w_r*R - w_c*C - w_s*S
        val obs = createObservation(
            eventType = "NOTE",
            title = "A".repeat(100),
            description = "B".repeat(100),
            observedAtDevice = System.currentTimeMillis()
        )
        val breakdown = scheduler.scoreBreakdown(obs)
        val u = breakdown["urgency"]!!
        val n = breakdown["novelty"]!!
        val g = breakdown["informationGain"]!!
        val r = breakdown["relevance"]!!
        val c = breakdown["cost"]!!
        val s = breakdown["staleness"]!!

        val expectedRaw = (0.30f * u) + (0.25f * n) + (0.25f * g) + (0.10f * r) - (0.05f * c) - (0.05f * s)
        val clampedRaw = expectedRaw.coerceIn(0f, 1f)

        assertEquals(clampedRaw, breakdown["rawScore"]!!, 1e-4f)
        assertEquals(breakdown["finalScore"]!!, scheduler.scoreObservation(obs), 1e-4f)
    }

    @Test
    fun testSafetyFloorOverrideForHazardWithMaxPenalties() {
        // HAZARD urgency is 1.0f >= 0.9f
        // Set maximum cost (huge size) and maximum staleness (1 day old)
        val oldHazard = createObservation(
            eventId = "hazard-old",
            eventType = "HAZARD",
            title = "X".repeat(1000),
            description = "Y".repeat(1500),
            observedAtDevice = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
        )

        val breakdown = scheduler.scoreBreakdown(oldHazard)
        val rawScore = breakdown["rawScore"]!!
        val finalScore = breakdown["finalScore"]!!

        assertEquals(1.0f, breakdown["urgency"]!!, 1e-4f)
        assertEquals(1.0f, breakdown["cost"]!!, 1e-4f)
        assertEquals(1.0f, breakdown["staleness"]!!, 1e-4f)
        assertEquals(1.0f, breakdown["safetyCritical"]!!, 1e-4f)

        // Raw score is depressed due to max penalties
        assertTrue("Raw score should be < 0.80 due to max penalties, was $rawScore", rawScore < 0.80f)
        // Safety floor override MUST ensure finalScore >= 0.80f
        assertEquals(0.80f, finalScore, 1e-4f)
        assertTrue("Hazard observation final score must be >= 0.80f", scheduler.scoreObservation(oldHazard) >= 0.80f)
    }

    @Test
    fun testSafetyFloorOverrideForBlockedRouteWithMaxPenalties() {
        // BLOCKED reportedStatus urgency is 0.95f >= 0.9f
        val oldBlocked = createObservation(
            eventId = "blocked-old",
            eventType = "ROUTE_STATUS",
            reportedStatus = "BLOCKED",
            title = "X".repeat(1000),
            description = "Y".repeat(1500),
            observedAtDevice = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
        )

        val breakdown = scheduler.scoreBreakdown(oldBlocked)
        val rawScore = breakdown["rawScore"]!!
        val finalScore = breakdown["finalScore"]!!

        assertEquals(0.95f, breakdown["urgency"]!!, 1e-4f)
        assertEquals(1.0f, breakdown["safetyCritical"]!!, 1e-4f)
        assertTrue("Raw score should be < 0.80, was $rawScore", rawScore < 0.80f)
        assertTrue("Blocked route observation final score must be >= 0.80f", finalScore >= 0.80f)
        assertEquals(0.80f, finalScore, 1e-4f)
    }

    @Test
    fun testNonSafetyCriticalObservationsDoNotReceiveSafetyFloor() {
        // NOTE has urgency 0.3f (< 0.9f)
        val oldNote = createObservation(
            eventId = "note-old",
            eventType = "NOTE",
            title = "X".repeat(1000),
            description = "Y".repeat(1500),
            observedAtDevice = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
        )

        val breakdown = scheduler.scoreBreakdown(oldNote)
        assertEquals(0.0f, breakdown["safetyCritical"]!!, 1e-4f)
        assertEquals(breakdown["rawScore"]!!, breakdown["finalScore"]!!, 1e-4f)
        assertTrue("Non-critical note should score well below safety floor", breakdown["finalScore"]!! < 0.50f)
    }

    @Test
    fun testSelectWithinBudgetHonorsPriorityOrderAndByteLimit() = runBlocking {
        // Create 3 observations: high priority (HAZARD), medium priority (OPEN route), low priority (NOTE)
        val obsHigh = createObservation(
            eventId = "obs-high",
            eventType = "HAZARD",
            title = "Critical Hazard",
            description = "Collapsed beam"
        )
        val obsMed = createObservation(
            eventId = "obs-med",
            eventType = "ROUTE_STATUS",
            reportedStatus = "OPEN",
            title = "Corridor Open",
            description = "Clear passage"
        )
        val obsLow = createObservation(
            eventId = "obs-low",
            eventType = "NOTE",
            title = "General Note",
            description = "Lighting is dim"
        )

        observationDao.insert(obsHigh)
        observationDao.insert(obsMed)
        observationDao.insert(obsLow)

        outboxDao.insert(OutboxEntity(eventId = "obs-low", priority = 0.2f, status = "PENDING"))
        outboxDao.insert(OutboxEntity(eventId = "obs-med", priority = 0.5f, status = "PENDING"))
        outboxDao.insert(OutboxEntity(eventId = "obs-high", priority = 0.9f, status = "PENDING"))

        // Budget large enough for all items
        val allSelected = scheduler.selectWithinBudget(maxBytes = 5000)
        assertEquals(3, allSelected.size)
        // Confirm sorted descending by score
        assertEquals("obs-high", allSelected[0].first)
        assertEquals("obs-med", allSelected[1].first)
        assertEquals("obs-low", allSelected[2].first)
        assertTrue(allSelected[0].second >= allSelected[1].second)
        assertTrue(allSelected[1].second >= allSelected[2].second)

        // Each observation's estimated bytes: (title.len + desc.len + 200) * 2
        // obsHigh: (15 + 14 + 200) * 2 = 458 bytes
        // With maxBytes = 460, only 1 observation can fit
        val restrictedBudget = scheduler.selectWithinBudget(maxBytes = 460)
        assertEquals(1, restrictedBudget.size)
        assertEquals("obs-high", restrictedBudget[0].first)

        // Zero budget should select nothing
        val zeroBudget = scheduler.selectWithinBudget(maxBytes = 0)
        assertTrue(zeroBudget.isEmpty())
    }

    @Test
    fun testSelectWithinBudgetWithMissingObservationGracefullySkipped() = runBlocking {
        outboxDao.insert(OutboxEntity(eventId = "ghost-obs", priority = 0.9f, status = "PENDING"))
        val selected = scheduler.selectWithinBudget(maxBytes = 1000)
        assertTrue(selected.isEmpty())
    }
}
