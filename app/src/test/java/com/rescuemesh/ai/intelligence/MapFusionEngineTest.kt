package com.rescuemesh.ai.intelligence

import com.rescuemesh.ai.data.entity.MapEdgeEntity
import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.fakes.FakeMapDao
import com.rescuemesh.ai.fakes.FakeObservationDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MapFusionEngineTest {

    private lateinit var mapDao: FakeMapDao
    private lateinit var observationDao: FakeObservationDao
    private lateinit var engine: MapFusionEngine

    private val missionId = "test-mission"

    @Before
    fun setUp() {
        mapDao = FakeMapDao()
        observationDao = FakeObservationDao()
        engine = MapFusionEngine(mapDao, observationDao)
    }

    private fun createEdge(
        edgeId: String,
        routeStatus: String = "UNKNOWN",
        fromNodeId: String = "node-A",
        toNodeId: String = "node-B",
        distance: Float = 15.0f,
        lastObservationId: String? = null,
        conflictingIds: String? = null
    ): MapEdgeEntity {
        return MapEdgeEntity(
            edgeId = edgeId,
            missionId = missionId,
            fromNodeId = fromNodeId,
            toNodeId = toNodeId,
            distance = distance,
            routeStatus = routeStatus,
            lastObservationId = lastObservationId,
            conflictingObservationIds = conflictingIds
        )
    }

    private fun createObservation(
        eventId: String,
        edgeId: String?,
        reportedStatus: String?,
        verificationStatus: String = "UNVERIFIED"
    ): ObservationEntity {
        return ObservationEntity(
            eventId = eventId,
            missionId = missionId,
            authorDeviceId = "device-1",
            authorSequence = 1L,
            eventType = "ROUTE_STATUS",
            title = "Edge Observation",
            reportedStatus = reportedStatus,
            mapEdgeId = edgeId,
            verificationStatus = verificationStatus,
            observedAtDevice = System.currentTimeMillis()
        )
    }

    @Test
    fun testUnknownToReportedOpenTransition() = runBlocking {
        mapDao.insertEdge(createEdge("edge-1", routeStatus = "UNKNOWN"))

        val obsOpen = createObservation("obs-open-1", "edge-1", "OPEN")
        val resultOpen = engine.applyObservation(obsOpen)

        assertNotNull(resultOpen)
        assertEquals("edge-1", resultOpen!!.edgeId)
        assertEquals("UNKNOWN", resultOpen.previousStatus)
        assertEquals("REPORTED_OPEN", resultOpen.newStatus)
        assertFalse(resultOpen.isConflict)
        assertEquals(listOf("obs-open-1"), resultOpen.conflictingIds)

        // Verify updated in DAO
        val updatedEdge = mapDao.getEdge("edge-1")
        assertNotNull(updatedEdge)
        assertEquals("REPORTED_OPEN", updatedEdge!!.routeStatus)
        assertEquals("obs-open-1", updatedEdge.lastObservationId)

        // Test with "CLEAR" report
        mapDao.insertEdge(createEdge("edge-clear", routeStatus = "UNKNOWN"))
        val obsClear = createObservation("obs-clear-1", "edge-clear", "CLEAR")
        val resultClear = engine.applyObservation(obsClear)

        assertNotNull(resultClear)
        assertEquals("REPORTED_OPEN", resultClear!!.newStatus)
    }

    @Test
    fun testUnknownToReportedBlockedTransition() = runBlocking {
        mapDao.insertEdge(createEdge("edge-blocked", routeStatus = "UNKNOWN"))
        val obsBlocked = createObservation("obs-blocked-1", "edge-blocked", "BLOCKED")
        val resultBlocked = engine.applyObservation(obsBlocked)

        assertNotNull(resultBlocked)
        assertEquals("UNKNOWN", resultBlocked!!.previousStatus)
        assertEquals("REPORTED_BLOCKED", resultBlocked.newStatus)
        assertFalse(resultBlocked.isConflict)
        assertEquals(listOf("obs-blocked-1"), resultBlocked.conflictingIds)

        // Test with "HAZARD" report
        mapDao.insertEdge(createEdge("edge-hazard", routeStatus = "UNKNOWN"))
        val obsHazard = createObservation("obs-hazard-1", "edge-hazard", "HAZARD")
        val resultHazard = engine.applyObservation(obsHazard)

        assertNotNull(resultHazard)
        assertEquals("REPORTED_BLOCKED", resultHazard!!.newStatus)
    }

    @Test
    fun testConflictPreservationWhenOpenEdgeReceivesBlockedReport() = runBlocking {
        // Step 1: Initialize edge as UNKNOWN and report OPEN
        mapDao.insertEdge(createEdge("edge-1", routeStatus = "UNKNOWN"))
        val obs1 = createObservation("obs-1", "edge-1", "OPEN")
        engine.applyObservation(obs1)

        // Verify current edge is REPORTED_OPEN with lastObservationId = "obs-1"
        val currentEdge = mapDao.getEdge("edge-1")!!
        assertEquals("REPORTED_OPEN", currentEdge.routeStatus)
        assertEquals("obs-1", currentEdge.lastObservationId)

        // Step 2: Conflicting observation reports BLOCKED
        val obs2 = createObservation("obs-2", "edge-1", "BLOCKED")
        val result = engine.applyObservation(obs2)

        assertNotNull(result)
        assertEquals("REPORTED_OPEN", result!!.previousStatus)
        assertEquals("CONFLICTED", result.newStatus)
        assertTrue(result.isConflict)

        // Both old and new observation IDs must be preserved in conflict history
        assertTrue("Conflict list must contain previous observation obs-1", result.conflictingIds.contains("obs-1"))
        assertTrue("Conflict list must contain new observation obs-2", result.conflictingIds.contains("obs-2"))

        // Verify DAO edge state
        val edgeInDao = mapDao.getEdge("edge-1")!!
        assertEquals("CONFLICTED", edgeInDao.routeStatus)
        val storedConflictIds = edgeInDao.conflictingObservationIds?.split(",") ?: emptyList()
        assertTrue(storedConflictIds.contains("obs-1"))
        assertTrue(storedConflictIds.contains("obs-2"))
    }

    @Test
    fun testConflictPreservationWhenBlockedEdgeReceivesOpenReport() = runBlocking {
        // Edge is REPORTED_BLOCKED with last observation obs-blocked
        mapDao.insertEdge(
            createEdge(
                "edge-rev",
                routeStatus = "REPORTED_BLOCKED",
                lastObservationId = "obs-blocked",
                conflictingIds = "obs-blocked"
            )
        )

        val obsOpen = createObservation("obs-open", "edge-rev", "OPEN")
        val result = engine.applyObservation(obsOpen)

        assertNotNull(result)
        assertEquals("REPORTED_BLOCKED", result!!.previousStatus)
        assertEquals("CONFLICTED", result.newStatus)
        assertTrue(result.isConflict)
        assertTrue(result.conflictingIds.contains("obs-blocked"))
        assertTrue(result.conflictingIds.contains("obs-open"))
    }

    @Test
    fun testReinforcedReportsDoNotProduceConflict() = runBlocking {
        // REPORTED_OPEN reinforced by another OPEN report
        mapDao.insertEdge(
            createEdge(
                "edge-open",
                routeStatus = "REPORTED_OPEN",
                lastObservationId = "obs-open-1",
                conflictingIds = "obs-open-1"
            )
        )

        val obsOpen2 = createObservation("obs-open-2", "edge-open", "OPEN")
        val resultOpen = engine.applyObservation(obsOpen2)

        assertNotNull(resultOpen)
        assertEquals("REPORTED_OPEN", resultOpen!!.newStatus)
        assertFalse(resultOpen.isConflict)

        // REPORTED_BLOCKED reinforced by another BLOCKED report
        mapDao.insertEdge(
            createEdge(
                "edge-blocked",
                routeStatus = "REPORTED_BLOCKED",
                lastObservationId = "obs-b-1",
                conflictingIds = "obs-b-1"
            )
        )

        val obsBlocked2 = createObservation("obs-b-2", "edge-blocked", "BLOCKED")
        val resultBlocked = engine.applyObservation(obsBlocked2)

        assertNotNull(resultBlocked)
        assertEquals("REPORTED_BLOCKED", resultBlocked!!.newStatus)
        assertFalse(resultBlocked.isConflict)
    }

    @Test
    fun testVerifiedEdgesCannotBeOverriddenByUnverifiedObservations() = runBlocking {
        // VERIFIED_OPEN edge
        mapDao.insertEdge(
            createEdge(
                "edge-verified-open",
                routeStatus = "VERIFIED_OPEN",
                lastObservationId = "obs-surveyor"
            )
        )

        val obsBlocked = createObservation("obs-unverified-blocked", "edge-verified-open", "BLOCKED")
        val resultOpen = engine.applyObservation(obsBlocked)

        assertNotNull(resultOpen)
        assertEquals("VERIFIED_OPEN", resultOpen!!.newStatus)
        assertFalse(resultOpen.isConflict)
        assertEquals("VERIFIED_OPEN", mapDao.getEdge("edge-verified-open")!!.routeStatus)

        // VERIFIED_BLOCKED edge
        mapDao.insertEdge(
            createEdge(
                "edge-verified-blocked",
                routeStatus = "VERIFIED_BLOCKED",
                lastObservationId = "obs-surveyor-2"
            )
        )

        val obsOpen = createObservation("obs-unverified-open", "edge-verified-blocked", "OPEN")
        val resultBlocked = engine.applyObservation(obsOpen)

        assertNotNull(resultBlocked)
        assertEquals("VERIFIED_BLOCKED", resultBlocked!!.newStatus)
        assertFalse(resultBlocked.isConflict)
        assertEquals("VERIFIED_BLOCKED", mapDao.getEdge("edge-verified-blocked")!!.routeStatus)
    }

    @Test
    fun testSubsequentReportOnConflictedEdgeAppendsToConflictHistory() = runBlocking {
        mapDao.insertEdge(
            createEdge(
                "edge-conflicted",
                routeStatus = "CONFLICTED",
                conflictingIds = "obs-1,obs-2"
            )
        )

        val obs3 = createObservation("obs-3", "edge-conflicted", "OPEN")
        val result = engine.applyObservation(obs3)

        assertNotNull(result)
        assertEquals("CONFLICTED", result!!.newStatus)
        assertTrue(result.isConflict)
        assertEquals(listOf("obs-1", "obs-2", "obs-3"), result.conflictingIds)
    }

    @Test
    fun testGetMapSummaryComputesCorrectCounts() = runBlocking {
        // Insert various edges for this mission
        mapDao.insertEdge(createEdge("e1", routeStatus = "UNKNOWN"))
        mapDao.insertEdge(createEdge("e2", routeStatus = "UNKNOWN"))
        mapDao.insertEdge(createEdge("e3", routeStatus = "REPORTED_OPEN"))
        mapDao.insertEdge(createEdge("e4", routeStatus = "VERIFIED_OPEN"))
        mapDao.insertEdge(createEdge("e5", routeStatus = "REPORTED_BLOCKED"))
        mapDao.insertEdge(createEdge("e6", routeStatus = "VERIFIED_BLOCKED"))
        mapDao.insertEdge(createEdge("e7", routeStatus = "CONFLICTED"))
        mapDao.insertEdge(createEdge("e8", routeStatus = "CONFLICTED"))

        // Edge for another mission should not be counted
        mapDao.insertEdge(
            MapEdgeEntity(
                edgeId = "other-mission-edge",
                missionId = "different-mission",
                fromNodeId = "n1",
                toNodeId = "n2",
                distance = 10f,
                routeStatus = "REPORTED_OPEN"
            )
        )

        val summary = engine.getMapSummary(missionId)

        assertEquals(8, summary.totalEdges)
        assertEquals(2, summary.unknownEdges)
        assertEquals(2, summary.openEdges)       // REPORTED_OPEN (1) + VERIFIED_OPEN (1)
        assertEquals(2, summary.blockedEdges)    // REPORTED_BLOCKED (1) + VERIFIED_BLOCKED (1)
        assertEquals(2, summary.conflictedEdges) // CONFLICTED (2)
    }

    @Test
    fun testApplyObservationReturnsNullForInvalidInputs() = runBlocking {
        // Missing edgeId
        val obsNoEdge = createObservation("obs-no-edge", null, "OPEN")
        assertNull(engine.applyObservation(obsNoEdge))

        // Missing reportedStatus
        val obsNoStatus = createObservation("obs-no-status", "edge-1", null)
        assertNull(engine.applyObservation(obsNoStatus))

        // Edge does not exist in MapDao
        val obsNonExistentEdge = createObservation("obs-missing-edge", "edge-404", "OPEN")
        assertNull(engine.applyObservation(obsNonExistentEdge))
    }
}
