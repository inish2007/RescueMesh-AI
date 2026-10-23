package com.rescuemesh.ai.gate1

import androidx.compose.ui.geometry.Offset
import com.rescuemesh.ai.data.entity.MapEdgeEntity
import com.rescuemesh.ai.data.entity.MapNodeEntity
import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.data.entity.OutboxEntity
import com.rescuemesh.ai.fakes.FakeMapDao
import com.rescuemesh.ai.fakes.FakeObservationDao
import com.rescuemesh.ai.fakes.FakeOutboxDao
import com.rescuemesh.ai.intelligence.MapFusionEngine
import com.rescuemesh.ai.intelligence.PriorityScheduler
import com.rescuemesh.ai.intelligence.RoutePlanner
import com.rescuemesh.ai.ui.screens.MapCoordinateProjector
import com.rescuemesh.ai.ui.screens.SituationalPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.sqrt

/**
 * Gate 1 Deep Verification Test Suite.
 *
 * Exhaustive adversarial testing verifying system invariants and core algorithms:
 * 1. [MapCoordinateProjector]: boundary clamping, bearing angles, metric precision, uncertainty bounds.
 * 2. [PriorityScheduler]: Invariant 5 (Absolute Safety Floor >= 0.80), clock skew handling, queue prioritization.
 * 3. [RoutePlanner]: Invariant 4 (Zero "safe" terminology), cyclic graphs, disconnected nodes, risk penalties.
 * 4. [MapFusionEngine]: Invariant 3 (Conflict-preserving state machine, persistent blocked passage rules).
 */
class Gate1DeepVerificationTest {

    private lateinit var mapDao: FakeMapDao
    private lateinit var observationDao: FakeObservationDao
    private lateinit var outboxDao: FakeOutboxDao

    private lateinit var scheduler: PriorityScheduler
    private lateinit var routePlanner: RoutePlanner
    private lateinit var fusionEngine: MapFusionEngine

    private val missionId = "GATE1_MISSION_VERIFY"

    @Before
    fun setUp() {
        mapDao = FakeMapDao()
        observationDao = FakeObservationDao()
        outboxDao = FakeOutboxDao()

        scheduler = PriorityScheduler(observationDao, mapDao, outboxDao)
        routePlanner = RoutePlanner(mapDao)
        fusionEngine = MapFusionEngine(mapDao, observationDao)
    }

    // =========================================================================
    // 1. MapCoordinateProjector Deep Verification
    // =========================================================================

    @Test
    fun testProjectSituationalCanvasBoundaryClampingExactBoundary() {
        val centerLat = 12.9716
        val centerLon = 77.5946
        val radiusMeters = 500f
        val canvasWidth = 1000f
        val canvasHeight = 1000f
        val paddingPx = 50f

        // 1 deg lat ≈ 111132.95m. For exactly 500m North:
        val deltaLatExact500m = 500.0 / 111132.95

        val pointBoundaryNorth = SituationalPoint(
            id = "bound-north",
            latitude = centerLat + deltaLatExact500m,
            longitude = centerLon,
            label = "500m Boundary North",
            type = "NOTE"
        )

        val layout = MapCoordinateProjector.projectSituationalCanvas(
            centerLat = centerLat,
            centerLon = centerLon,
            points = listOf(pointBoundaryNorth),
            width = canvasWidth,
            height = canvasHeight,
            radiusMeters = radiusMeters,
            paddingPx = paddingPx
        )

        val projPoint = layout.projectedPoints.first()

        // Exact boundary point should be within or exactly at boundary bounds
        assertTrue("Point at boundary distance must be considered within bounds", projPoint.isWithinBounds)
        assertEquals(500.0f, projPoint.distanceMeters, 0.5f)

        // Distance from center canvas in pixels should match radiusPx (450px)
        val distPxFromCenter = (projPoint.canvasOffset - layout.centerCanvasOffset).getDistance()
        assertEquals(layout.radiusPx, distPxFromCenter, 0.5f)
        assertEquals(0f, projPoint.bearingDegrees, 0.1f)
    }

    @Test
    fun testProjectSituationalCanvasClampingBeyond500mAcrossQuadrants() {
        val centerLat = 20.0
        val centerLon = 80.0
        val radiusMeters = 500f
        val canvasWidth = 800f
        val canvasHeight = 800f
        val paddingPx = 40f

        // Coordinates well outside 500m: 1 km, 5 km, 20 km
        val pNorthEast = SituationalPoint(
            id = "p-ne",
            latitude = centerLat + 0.05, // ~5.5 km North
            longitude = centerLon + 0.05, // ~5.2 km East
            label = "Far North-East",
            type = "HAZARD"
        )
        val pSouthWest = SituationalPoint(
            id = "p-sw",
            latitude = centerLat - 0.1, // ~11 km South
            longitude = centerLon - 0.1, // ~10.4 km West
            label = "Far South-West",
            type = "SOS"
        )

        val layout = MapCoordinateProjector.projectSituationalCanvas(
            centerLat = centerLat,
            centerLon = centerLon,
            points = listOf(pNorthEast, pSouthWest),
            width = canvasWidth,
            height = canvasHeight,
            radiusMeters = radiusMeters,
            paddingPx = paddingPx
        )

        assertEquals(2, layout.projectedPoints.size)

        for (proj in layout.projectedPoints) {
            assertFalse("${proj.point.id} must be flagged as outside bounds", proj.isWithinBounds)
            assertTrue("Distance must exceed radius", proj.distanceMeters > radiusMeters)

            // Clamping invariant: offset must be strictly on perimeter circle (dist from center == radiusPx)
            val distPx = (proj.canvasOffset - layout.centerCanvasOffset).getDistance()
            assertEquals("Clamped point must lie on circumference", layout.radiusPx, distPx, 0.5f)
        }

        val projNE = layout.projectedPoints.first { it.point.id == "p-ne" }
        val projSW = layout.projectedPoints.first { it.point.id == "p-sw" }

        assertTrue("NE point must have bearing in (0, 90)", projNE.bearingDegrees in 40f..50f)
        assertTrue("SW point must have bearing in (180, 270)", projSW.bearingDegrees in 220f..230f)
    }

    @Test
    fun testProjectSituationalCanvasExtremeCoordinatesSafety() {
        val extremeCases = listOf(
            Triple(90.0, 0.0, "North Pole"),
            Triple(-90.0, 0.0, "South Pole"),
            Triple(0.0, 0.0, "Equator Null Island"),
            Triple(0.0, 180.0, "Antimeridian East"),
            Triple(0.0, -180.0, "Antimeridian West")
        )

        for ((lat, lon, label) in extremeCases) {
            val point = SituationalPoint(
                id = "ext-$label",
                latitude = lat,
                longitude = lon,
                label = label,
                type = "NOTE"
            )

            val layout = MapCoordinateProjector.projectSituationalCanvas(
                centerLat = lat,
                centerLon = lon,
                points = listOf(point),
                width = 500f,
                height = 500f
            )

            val proj = layout.projectedPoints.first()
            assertFalse("$label distance must not be NaN", proj.distanceMeters.isNaN())
            assertFalse("$label distance must not be infinite", proj.distanceMeters.isInfinite())
            assertFalse("$label bearing must not be NaN", proj.bearingDegrees.isNaN())
            assertFalse("$label X offset must not be NaN", proj.canvasOffset.x.isNaN())
            assertFalse("$label Y offset must not be NaN", proj.canvasOffset.y.isNaN())
            assertEquals(0f, proj.distanceMeters, 1e-4f)
        }
    }

    @Test
    fun testBearingAngleCalculationsAllCardinalsAndIntercardinals() {
        val centerLat = 0.0
        val centerLon = 0.0
        val step = 0.001 // ~111m

        val cardinals = listOf(
            SituationalPoint("N", centerLat + step, centerLon, "North", "NOTE") to 0f,
            SituationalPoint("E", centerLat, centerLon + step, "East", "NOTE") to 90f,
            SituationalPoint("S", centerLat - step, centerLon, "South", "NOTE") to 180f,
            SituationalPoint("W", centerLat, centerLon - step, "West", "NOTE") to 270f
        )

        val layoutCardinals = MapCoordinateProjector.projectSituationalCanvas(
            centerLat = centerLat,
            centerLon = centerLon,
            points = cardinals.map { it.first },
            width = 1000f,
            height = 1000f
        )

        for ((pt, expectedBearing) in cardinals) {
            val proj = layoutCardinals.projectedPoints.first { it.point.id == pt.id }
            assertEquals(
                "Bearing for ${pt.label} must match expected compass angle",
                expectedBearing,
                proj.bearingDegrees,
                0.15f
            )
        }

        // Intercardinals: NE (45°), SE (135°), SW (225°), NW (315°)
        // At equator, 1 deg lat ≈ 111132.95m and 1 deg lon ≈ 111320m, so deltaLat == deltaLon gives ~45°
        val intercardinals = listOf(
            SituationalPoint("NE", centerLat + step, centerLon + step, "NE", "NOTE") to 45f,
            SituationalPoint("SE", centerLat - step, centerLon + step, "SE", "NOTE") to 135f,
            SituationalPoint("SW", centerLat - step, centerLon - step, "SW", "NOTE") to 225f,
            SituationalPoint("NW", centerLat + step, centerLon - step, "NW", "NOTE") to 315f
        )

        val layoutInter = MapCoordinateProjector.projectSituationalCanvas(
            centerLat = centerLat,
            centerLon = centerLon,
            points = intercardinals.map { it.first },
            width = 1000f,
            height = 1000f
        )

        for ((pt, expectedBearing) in intercardinals) {
            val proj = layoutInter.projectedPoints.first { it.point.id == pt.id }
            assertEquals(
                "Bearing for ${pt.label} must be ~${expectedBearing}°",
                expectedBearing,
                proj.bearingDegrees,
                1.0f
            )
        }
    }

    @Test
    fun testDistanceCalculationPrecisionAt0m10mAnd1000m() {
        val centerLat = 0.0
        val centerLon = 0.0

        // 1 deg lat = 111132.95 meters
        val deltaLat10m = 10.0 / 111132.95
        val deltaLat1000m = 1000.0 / 111132.95

        val pt0m = SituationalPoint("pt-0m", centerLat, centerLon, "0m", "NOTE")
        val pt10m = SituationalPoint("pt-10m", centerLat + deltaLat10m, centerLon, "10m", "NOTE")
        val pt1000m = SituationalPoint("pt-1000m", centerLat + deltaLat1000m, centerLon, "1000m", "NOTE")

        val layout = MapCoordinateProjector.projectSituationalCanvas(
            centerLat = centerLat,
            centerLon = centerLon,
            points = listOf(pt0m, pt10m, pt1000m),
            width = 1200f,
            height = 1200f,
            radiusMeters = 1500f
        )

        val proj0m = layout.projectedPoints.first { it.point.id == "pt-0m" }
        val proj10m = layout.projectedPoints.first { it.point.id == "pt-10m" }
        val proj1000m = layout.projectedPoints.first { it.point.id == "pt-1000m" }

        assertEquals(0f, proj0m.distanceMeters, 1e-4f)
        assertEquals(10.0f, proj10m.distanceMeters, 0.05f)
        assertEquals(1000.0f, proj1000m.distanceMeters, 0.5f)
    }

    @Test
    fun testUncertaintyCirclePixelRadiusBounds() {
        val centerLat = 10.0
        val centerLon = 20.0
        val radiusMeters = 500f
        val width = 1000f
        val height = 1000f
        val padding = 50f
        // usable = 900px, radiusPx = 450px, pixelsPerMeter = 450 / 500 = 0.9 px/m

        val ptNull = SituationalPoint("p-null", centerLat, centerLon, "Null", "NOTE", accuracy = null)
        val ptZero = SituationalPoint("p-zero", centerLat, centerLon, "Zero", "NOTE", accuracy = 0f)
        val ptNeg = SituationalPoint("p-neg", centerLat, centerLon, "Negative", "NOTE", accuracy = -50f)
        val ptTiny = SituationalPoint("p-tiny", centerLat, centerLon, "Tiny", "NOTE", accuracy = 0.5f) // 0.5 * 0.9 = 0.45px -> clamped to 4f
        val ptMid = SituationalPoint("p-mid", centerLat, centerLon, "Mid", "NOTE", accuracy = 100f) // 100 * 0.9 = 90px
        val ptHuge = SituationalPoint("p-huge", centerLat, centerLon, "Huge", "NOTE", accuracy = 10000f) // Clamped to radiusPx (450f)

        val layout = MapCoordinateProjector.projectSituationalCanvas(
            centerLat = centerLat,
            centerLon = centerLon,
            points = listOf(ptNull, ptZero, ptNeg, ptTiny, ptMid, ptHuge),
            width = width,
            height = height,
            radiusMeters = radiusMeters,
            paddingPx = padding
        )

        val m = layout.projectedPoints.associateBy { it.point.id }

        assertEquals(0f, m["p-null"]!!.accuracyRadiusPx, 1e-4f)
        assertEquals(0f, m["p-zero"]!!.accuracyRadiusPx, 1e-4f)
        assertEquals(0f, m["p-neg"]!!.accuracyRadiusPx, 1e-4f)

        // Lower bound clamp verification: minimum 4f
        assertEquals(4f, m["p-tiny"]!!.accuracyRadiusPx, 1e-4f)

        // Middle calculation: 100m * 0.9 px/m = 90f
        assertEquals(90f, m["p-mid"]!!.accuracyRadiusPx, 0.5f)

        // Upper bound clamp verification: clamped to layout.radiusPx (450f)
        assertEquals(layout.radiusPx, m["p-huge"]!!.accuracyRadiusPx, 0.5f)
    }

    // =========================================================================
    // 2. PriorityScheduler Invariant 5 & Clock Skew Verification
    // =========================================================================

    private fun buildAdversarialObservation(
        eventId: String,
        eventType: String,
        reportedStatus: String? = null,
        verificationStatus: String = "UNVERIFIED",
        titleLen: Int = 1000,
        descLen: Int = 2000,
        ageMs: Long = 10 * 86400 * 1000L, // 10 days old
        isLocal: Boolean = false,
        mapEdgeId: String? = null
    ): ObservationEntity {
        return ObservationEntity(
            eventId = eventId,
            missionId = missionId,
            authorDeviceId = "remote-node-99",
            authorSequence = 42L,
            eventType = eventType,
            title = "A".repeat(titleLen),
            description = "B".repeat(descLen),
            reportedStatus = reportedStatus,
            verificationStatus = verificationStatus,
            mapEdgeId = mapEdgeId,
            mapNodeId = null,
            isLocal = isLocal,
            observedAtDevice = System.currentTimeMillis() - ageMs
        )
    }

    @Test
    fun testInvariant5AbsoluteSafetyFloorSOSUnderMaximumAdversarialPenalties() {
        val worstCaseSOS = buildAdversarialObservation(
            eventId = "sos-worst",
            eventType = "SOS",
            ageMs = 30 * 86400 * 1000L // 30 days old
        )

        val breakdown = scheduler.scoreBreakdown(worstCaseSOS)
        val finalScore = scheduler.scoreObservation(worstCaseSOS)

        // Verify worst-case penalties were applied
        assertEquals(1.0f, breakdown["staleness"]!!, 1e-4f)
        assertEquals(1.0f, breakdown["cost"]!!, 1e-4f)
        assertEquals(1.0f, breakdown["safetyCritical"]!!, 1e-4f)

        // Raw score is depressed, but Invariant 5 floor MUST hold: P >= 0.80
        assertTrue("Raw score before floor should be depressed", breakdown["rawScore"]!! < 0.80f)
        assertTrue("Invariant 5 violated: SOS score must be >= 0.80f", finalScore >= 0.80f)
        assertEquals(0.80f, finalScore, 1e-4f)
    }

    @Test
    fun testInvariant5AbsoluteSafetyFloorHazardUnderMaximumAdversarialPenalties() {
        val worstCaseHazard = buildAdversarialObservation(
            eventId = "hazard-worst",
            eventType = "HAZARD",
            ageMs = 30 * 86400 * 1000L
        )

        val breakdown = scheduler.scoreBreakdown(worstCaseHazard)
        val finalScore = scheduler.scoreObservation(worstCaseHazard)

        assertEquals(1.0f, breakdown["safetyCritical"]!!, 1e-4f)
        assertTrue("Invariant 5 violated: HAZARD score must be >= 0.80f", finalScore >= 0.80f)
        assertEquals(0.80f, finalScore, 1e-4f)
    }

    @Test
    fun testInvariant5AbsoluteSafetyFloorBlockedPassagesUnderMaximumAdversarialPenalties() {
        val blockedStatuses = listOf("BLOCKED", "REPORTED_BLOCKED", "VERIFIED_BLOCKED")

        for (status in blockedStatuses) {
            val obs = buildAdversarialObservation(
                eventId = "blocked-$status",
                eventType = "ROUTE_STATUS",
                reportedStatus = status
            )

            val breakdown = scheduler.scoreBreakdown(obs)
            val score = scheduler.scoreObservation(obs)

            assertEquals(0.95f, breakdown["urgency"]!!, 1e-4f)
            assertEquals(1.0f, breakdown["safetyCritical"]!!, 1e-4f)
            assertTrue("Invariant 5 violated: $status must score >= 0.80f", score >= 0.80f)
        }
    }

    @Test
    fun testNonSafetyCriticalObservationsReceiveNoFloorUnderPenalties() {
        val nonCriticalCases = listOf(
            buildAdversarialObservation("note-stale", "NOTE"),
            buildAdversarialObservation("photo-stale", "PHOTO"),
            buildAdversarialObservation("route-open-stale", "ROUTE_STATUS", reportedStatus = "OPEN")
        )

        for (obs in nonCriticalCases) {
            val breakdown = scheduler.scoreBreakdown(obs)
            val score = scheduler.scoreObservation(obs)

            assertEquals(0.0f, breakdown["safetyCritical"]!!, 1e-4f)
            assertTrue("Non-critical event should score below safety floor 0.80", score < 0.80f)
        }
    }

    @Test
    fun testClockSkewFutureTimestampsHandledSafelyWithoutOverflowOrNaN() {
        val now = System.currentTimeMillis()
        val futureDeltas = listOf(
            1_000L,              // 1 sec into future
            3_600_000L,          // 1 hour into future
            86_400_000L * 365,   // 1 year into future
            Long.MAX_VALUE / 4   // Extreme future timestamp
        )

        for (delta in futureDeltas) {
            val futureObs = ObservationEntity(
                eventId = "future-$delta",
                missionId = missionId,
                authorDeviceId = "dev-1",
                authorSequence = 1L,
                eventType = "NOTE",
                title = "Future Note",
                description = "Clock skew test",
                observedAtDevice = now + delta
            )

            val breakdown = scheduler.scoreBreakdown(futureObs)
            val staleness = breakdown["staleness"]!!
            val finalScore = scheduler.scoreObservation(futureObs)

            // Future timestamp should produce 0.0 staleness (maximum freshness), never negative or NaN
            assertEquals("Staleness for future timestamp must coerce to 0.0", 0.0f, staleness, 1e-4f)
            assertFalse("Final score must not be NaN", finalScore.isNaN())
            assertTrue("Final score must remain within [0.0, 1.0]", finalScore in 0.0f..1.0f)
        }
    }

    @Test
    fun testClockSkewAncientTimestampsHandledSafely() {
        val ancientTimestamps = listOf(
            0L,                  // Epoch 1970
            -1_000_000L,         // Negative timestamp before Epoch
            Long.MIN_VALUE / 4   // Extreme past timestamp
        )

        for (ts in ancientTimestamps) {
            val ancientObs = ObservationEntity(
                eventId = "ancient-$ts",
                missionId = missionId,
                authorDeviceId = "dev-1",
                authorSequence = 1L,
                eventType = "NOTE",
                title = "Ancient Note",
                description = "Clock skew ancient test",
                observedAtDevice = ts
            )

            val breakdown = scheduler.scoreBreakdown(ancientObs)
            val staleness = breakdown["staleness"]!!
            val finalScore = scheduler.scoreObservation(ancientObs)

            assertEquals("Staleness for ancient timestamp must clamp to 1.0", 1.0f, staleness, 1e-4f)
            assertFalse("Score must not be NaN", finalScore.isNaN())
            assertTrue("Score must remain within [0.0, 1.0]", finalScore in 0.0f..1.0f)
        }
    }

    @Test
    fun testZeroInformationGainAndMaximumQueuePrioritization() = runBlocking {
        // High urgency, high gain
        val obsSOS = ObservationEntity(
            eventId = "q-sos", missionId = missionId, authorDeviceId = "d1", authorSequence = 1L,
            eventType = "SOS", title = "SOS", description = "Critical rescue", observedAtDevice = System.currentTimeMillis()
        )
        // High urgency hazard
        val obsHazard = ObservationEntity(
            eventId = "q-hazard", missionId = missionId, authorDeviceId = "d1", authorSequence = 2L,
            eventType = "HAZARD", title = "Fire", description = "Structural fire", observedAtDevice = System.currentTimeMillis()
        )
        // Blocked route
        val obsBlocked = ObservationEntity(
            eventId = "q-blocked", missionId = missionId, authorDeviceId = "d1", authorSequence = 3L,
            eventType = "ROUTE_STATUS", reportedStatus = "BLOCKED", title = "Blocked", description = "Debris", observedAtDevice = System.currentTimeMillis()
        )
        // Verified route
        val obsVerified = ObservationEntity(
            eventId = "q-verified", missionId = missionId, authorDeviceId = "d1", authorSequence = 4L,
            eventType = "ROUTE_STATUS", reportedStatus = "OPEN", verificationStatus = "VERIFIED",
            title = "Surveyed Passage", description = "Confirmed safe by engineer", observedAtDevice = System.currentTimeMillis()
        )
        // Zero-gain / low priority note: remote, large, stale, unverified
        val obsZeroGain = ObservationEntity(
            eventId = "q-zero-gain", missionId = missionId, authorDeviceId = "d2", authorSequence = 5L,
            eventType = "NOTE", title = "Z".repeat(500), description = "W".repeat(1000),
            observedAtDevice = System.currentTimeMillis() - (24 * 3600 * 1000L), isLocal = false
        )

        observationDao.insertAll(listOf(obsSOS, obsHazard, obsBlocked, obsVerified, obsZeroGain))

        // Put all into outbox
        outboxDao.insert(OutboxEntity(eventId = "q-zero-gain", priority = 0.1f, status = "PENDING"))
        outboxDao.insert(OutboxEntity(eventId = "q-verified", priority = 0.5f, status = "PENDING"))
        outboxDao.insert(OutboxEntity(eventId = "q-blocked", priority = 0.7f, status = "PENDING"))
        outboxDao.insert(OutboxEntity(eventId = "q-hazard", priority = 0.8f, status = "PENDING"))
        outboxDao.insert(OutboxEntity(eventId = "q-sos", priority = 0.9f, status = "PENDING"))

        val queueSelected = scheduler.selectWithinBudget(maxBytes = 100_000)
        assertEquals(5, queueSelected.size)

        // Strict monotonicity check: priority must be non-increasing
        for (i in 0 until queueSelected.size - 1) {
            assertTrue(
                "Queue order must be non-increasing: ${queueSelected[i].second} >= ${queueSelected[i + 1].second}",
                queueSelected[i].second >= queueSelected[i + 1].second
            )
        }

        // Top items must be safety critical (SOS, HAZARD, BLOCKED)
        val top3EventIds = queueSelected.take(3).map { it.first }
        assertTrue(top3EventIds.contains("q-sos"))
        assertTrue(top3EventIds.contains("q-hazard"))
        assertTrue(top3EventIds.contains("q-blocked"))

        // Zero-gain stale note must be at the very bottom
        assertEquals("q-zero-gain", queueSelected.last().first)

        // Constrained budget: only enough for 1 safety critical event
        val singleBudget = scheduler.selectWithinBudget(maxBytes = 460)
        assertEquals(1, singleBudget.size)
        assertTrue(singleBudget.first().first in listOf("q-sos", "q-hazard", "q-blocked"))
    }

    // =========================================================================
    // 3. RoutePlanner Invariant 4 & Pathfinding Graph Scenarios
    // =========================================================================

    @Test
    fun testInvariant4ExhaustiveProhibitionOfSafeSubstringInOutputsAndReflection() = runBlocking {
        // Setup a comprehensive graph with varied edge states
        val nA = MapNodeEntity("A", missionId, "Sector Alpha", 0, 0.0, 0.0)
        val nB = MapNodeEntity("B", missionId, "Sector Bravo", 0, 0.001, 0.001)
        val nC = MapNodeEntity("C", missionId, "Sector Charlie", 0, 0.002, 0.002)
        val nD = MapNodeEntity("D", missionId, "Sector Delta", 0, 0.003, 0.003)
        mapDao.insertNodes(listOf(nA, nB, nC, nD))

        mapDao.insertEdges(listOf(
            MapEdgeEntity("e1", missionId, "A", "B", 10f, "REPORTED_OPEN"),
            MapEdgeEntity("e2", missionId, "B", "C", 10f, "CONFLICTED"),
            MapEdgeEntity("e3", missionId, "C", "D", 10f, "UNKNOWN")
        ))

        val result = routePlanner.findRoute("A", "D", missionId, avoidBlocked = false, avoidConflicted = false)

        // 1. Check all warnings for 'safe'
        for (w in result.warnings) {
            assertFalse(
                "Invariant 4 Violation: Warning contains 'safe': $w",
                w.contains("safe", ignoreCase = true)
            )
        }

        // 2. Check path labels
        for (label in result.pathLabels) {
            assertFalse(
                "Invariant 4 Violation: Path label contains 'safe': $label",
                label.contains("safe", ignoreCase = true)
            )
        }

        // 3. Reflection scan on RoutePlanner class members
        for (method: Method in RoutePlanner::class.java.declaredMethods) {
            assertFalse(
                "Invariant 4 Violation: Method '${method.name}' contains 'safe'",
                method.name.contains("safe", ignoreCase = true)
            )
        }

        for (field: Field in RoutePlanner::class.java.declaredFields) {
            assertFalse(
                "Invariant 4 Violation: Field '${field.name}' contains 'safe'",
                field.name.contains("safe", ignoreCase = true)
            )
        }

        // 4. Reflection scan on RouteResult class members
        for (field: Field in RoutePlanner.RouteResult::class.java.declaredFields) {
            assertFalse(
                "Invariant 4 Violation: RouteResult field '${field.name}' contains 'safe'",
                field.name.contains("safe", ignoreCase = true)
            )
        }
    }

    @Test
    fun testCyclicGraphPathfindingTerminatesAndFindsOptimalPath() = runBlocking {
        // Multi-cycle diamond topology:
        // A <-> B (10m)
        // B <-> C (10m)
        // C <-> A (25m cycle back to A)
        // B <-> D (5m)
        // D <-> E (15m)
        // C <-> E (10m)
        // Multiple alternative cycles leading to destination E
        val nodes = listOf("A", "B", "C", "D", "E").map {
            MapNodeEntity(it, missionId, "Node $it", 0, 0.0, 0.0)
        }
        mapDao.insertNodes(nodes)

        mapDao.insertEdges(listOf(
            MapEdgeEntity("e_AB", missionId, "A", "B", 10f, "VERIFIED_OPEN"),
            MapEdgeEntity("e_BC", missionId, "B", "C", 10f, "VERIFIED_OPEN"),
            MapEdgeEntity("e_CA", missionId, "C", "A", 25f, "VERIFIED_OPEN"),
            MapEdgeEntity("e_BD", missionId, "B", "D", 5f, "VERIFIED_OPEN"),
            MapEdgeEntity("e_DE", missionId, "D", "E", 15f, "VERIFIED_OPEN"),
            MapEdgeEntity("e_CE", missionId, "C", "E", 10f, "VERIFIED_OPEN")
        ))

        // Path A -> B -> D -> E cost: 10 + 5 + 15 = 30m
        // Path A -> B -> C -> E cost: 10 + 10 + 10 = 30m
        // Path A -> C -> E cost: 25 + 10 = 35m
        val result = routePlanner.findRoute("A", "E", missionId)

        assertTrue("Path must be successfully resolved in cyclic graph", result.isComplete)
        assertEquals(30.0, result.totalDistance, 1e-4)
        assertEquals(30.0, result.totalCost, 1e-4)
        assertEquals("A", result.path.first())
        assertEquals("E", result.path.last())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun testDisconnectedGraphAndMissingNodesHandling() = runBlocking {
        // Subgraph 1: A <-> B
        // Subgraph 2: C <-> D
        val nodes = listOf("A", "B", "C", "D").map {
            MapNodeEntity(it, missionId, "Node $it", 0, 0.0, 0.0)
        }
        mapDao.insertNodes(nodes)

        mapDao.insertEdges(listOf(
            MapEdgeEntity("e1", missionId, "A", "B", 10f, "VERIFIED_OPEN"),
            MapEdgeEntity("e2", missionId, "C", "D", 10f, "VERIFIED_OPEN")
        ))

        // Case 1: Route between disjoint components
        val disjointResult = routePlanner.findRoute("A", "D", missionId)
        assertFalse("Route between disconnected components must be incomplete", disjointResult.isComplete)
        assertTrue(disjointResult.path.isEmpty())
        assertEquals(0.0, disjointResult.totalCost, 1e-4)
        assertTrue(disjointResult.warnings.any { it.contains("No route found") })

        // Case 2: Non-existent destination node
        val missingDestResult = routePlanner.findRoute("A", "GHOST_NODE", missionId)
        assertFalse(missingDestResult.isComplete)
        assertTrue(missingDestResult.warnings.any { it.contains("No route found") })

        // Case 3: Start and destination identical
        val sameNodeResult = routePlanner.findRoute("A", "A", missionId)
        assertTrue(sameNodeResult.isComplete)
        assertEquals(listOf("A"), sameNodeResult.path)
        assertEquals(0.0, sameNodeResult.totalDistance, 1e-4)
    }

    @Test
    fun testConflictedEdgeRiskPenaltyForcesDetourOverShorterPassage() = runBlocking {
        // Start -> Direct -> Goal (Distance: 12m, CONFLICTED)
        // Start -> Detour1 -> Detour2 -> Goal (Distance: 20m + 20m + 20m = 60m, VERIFIED_OPEN)
        val nodes = listOf("S", "G", "D1", "D2").map {
            MapNodeEntity(it, missionId, "Node $it", 0, 0.0, 0.0)
        }
        mapDao.insertNodes(nodes)

        mapDao.insertEdges(listOf(
            MapEdgeEntity("direct_conflicted", missionId, "S", "G", 12f, "CONFLICTED"),
            MapEdgeEntity("detour_1", missionId, "S", "D1", 20f, "VERIFIED_OPEN"),
            MapEdgeEntity("detour_2", missionId, "D1", "D2", 20f, "VERIFIED_OPEN"),
            MapEdgeEntity("detour_3", missionId, "D2", "G", 20f, "VERIFIED_OPEN")
        ))

        // Default risk penalty is 100.0.
        // Direct cost = 12 + 100 = 112.0
        // Detour cost = 20 + 20 + 20 = 60.0
        // Planner MUST pick longer detour because 60.0 < 112.0
        val detourChoice = routePlanner.findRoute("S", "G", missionId)
        assertTrue(detourChoice.isComplete)
        assertEquals(listOf("S", "D1", "D2", "G"), detourChoice.path)
        assertEquals(60.0, detourChoice.totalCost, 1e-4)
        assertEquals(60.0, detourChoice.totalDistance, 1e-4)

        // If risk penalty is lowered below 48.0 (e.g. 10.0), direct cost = 12 + 10 = 22.0 < 60.0
        val directChoice = routePlanner.findRoute("S", "G", missionId, riskPenalty = 10.0)
        assertTrue(directChoice.isComplete)
        assertEquals(listOf("S", "G"), directChoice.path)
        assertEquals(22.0, directChoice.totalCost, 1e-4)
        assertEquals(12.0, directChoice.totalDistance, 1e-4)
        assertTrue("Warning must be generated for CONFLICTED traversal", directChoice.warnings.any { it.contains("CONFLICTED") })
    }

    // =========================================================================
    // 4. MapFusionEngine Invariant 3 & Blocked Persistence
    // =========================================================================

    @Test
    fun testInvariant3ConflictingObservationsPreserveEarlierEvidenceWithoutDataLoss() = runBlocking {
        val edge = MapEdgeEntity("test_edge", missionId, "N1", "N2", 15f, "UNKNOWN")
        mapDao.insertEdge(edge)

        // 1. Initial report: OPEN
        val obs1 = ObservationEntity(
            eventId = "obs_1", missionId = missionId, authorDeviceId = "dev-1", authorSequence = 1L,
            eventType = "ROUTE_STATUS", title = "Route Open", reportedStatus = "OPEN", mapEdgeId = "test_edge", observedAtDevice = System.currentTimeMillis()
        )
        val res1 = fusionEngine.applyObservation(obs1)!!
        assertEquals("REPORTED_OPEN", res1.newStatus)
        assertEquals(listOf("obs_1"), res1.conflictingIds)

        // 2. Conflicting report: BLOCKED
        val obs2 = ObservationEntity(
            eventId = "obs_2", missionId = missionId, authorDeviceId = "dev-2", authorSequence = 1L,
            eventType = "ROUTE_STATUS", title = "Route Blocked", reportedStatus = "BLOCKED", mapEdgeId = "test_edge", observedAtDevice = System.currentTimeMillis()
        )
        val res2 = fusionEngine.applyObservation(obs2)!!
        assertEquals("CONFLICTED", res2.newStatus)
        assertTrue(res2.isConflict)
        assertTrue(res2.conflictingIds.contains("obs_1"))
        assertTrue(res2.conflictingIds.contains("obs_2"))

        // 3. Third contradictory report: OPEN
        val obs3 = ObservationEntity(
            eventId = "obs_3", missionId = missionId, authorDeviceId = "dev-3", authorSequence = 1L,
            eventType = "ROUTE_STATUS", title = "Route Open Again", reportedStatus = "OPEN", mapEdgeId = "test_edge", observedAtDevice = System.currentTimeMillis()
        )
        val res3 = fusionEngine.applyObservation(obs3)!!
        assertEquals("CONFLICTED", res3.newStatus)
        assertTrue(res3.conflictingIds.contains("obs_1"))
        assertTrue(res3.conflictingIds.contains("obs_2"))
        assertTrue(res3.conflictingIds.contains("obs_3"))

        // Verify database persistence retains all 3 conflict IDs
        val storedEdge = mapDao.getEdge("test_edge")!!
        assertEquals("CONFLICTED", storedEdge.routeStatus)
        val conflictList = storedEdge.conflictingObservationIds!!.split(",")
        assertEquals(3, conflictList.size)
        assertEquals(listOf("obs_1", "obs_2", "obs_3"), conflictList)
    }

    @Test
    fun testReportedBlockedEdgesAreNeverClearedByTimeAlone() = runBlocking {
        // Initialize an edge that was REPORTED_BLOCKED 14 days ago
        val ancientTimestamp = System.currentTimeMillis() - (14 * 86400 * 1000L)
        val blockedEdge = MapEdgeEntity(
            edgeId = "ancient_blocked_edge",
            missionId = missionId,
            fromNodeId = "N1",
            toNodeId = "N2",
            distance = 20f,
            routeStatus = "REPORTED_BLOCKED",
            lastObservationId = "ancient_obs_1",
            conflictingObservationIds = "ancient_obs_1"
        )
        mapDao.insertEdge(blockedEdge)

        // Verify summary recognizes it as blocked
        val summary1 = fusionEngine.getMapSummary(missionId)
        assertEquals(1, summary1.blockedEdges)

        // Multiple unrelated observations applied to OTHER edges
        for (i in 1..5) {
            val otherEdge = MapEdgeEntity("other_edge_$i", missionId, "N1", "N2", 10f, "UNKNOWN")
            mapDao.insertEdge(otherEdge)
            val obs = ObservationEntity(
                eventId = "unrelated_obs_$i", missionId = missionId, authorDeviceId = "dev-1", authorSequence = i.toLong(),
                eventType = "ROUTE_STATUS", title = "Unrelated Edge $i", reportedStatus = "OPEN", mapEdgeId = "other_edge_$i", observedAtDevice = System.currentTimeMillis()
            )
            fusionEngine.applyObservation(obs)
        }

        // Verify ancient blocked edge status did NOT decay or change
        val edgeAfterTimeAndEvents = mapDao.getEdge("ancient_blocked_edge")!!
        assertEquals("REPORTED_BLOCKED", edgeAfterTimeAndEvents.routeStatus)

        // An unverified OPEN observation from a different responder CANNOT clear the blocked status
        val unverifiedOpenObs = ObservationEntity(
            eventId = "unverified_open_claim",
            missionId = missionId,
            authorDeviceId = "dev-casual",
            authorSequence = 100L,
            eventType = "ROUTE_STATUS",
            title = "Unverified Open Claim",
            reportedStatus = "OPEN",
            verificationStatus = "UNVERIFIED",
            mapEdgeId = "ancient_blocked_edge",
            observedAtDevice = System.currentTimeMillis()
        )
        val fusionResult = fusionEngine.applyObservation(unverifiedOpenObs)!!

        // Invariant 3: Conflicting unverified report turns it into CONFLICTED, NEVER clears it to OPEN
        assertEquals("CONFLICTED", fusionResult.newStatus)
        assertTrue(fusionResult.conflictingIds.contains("ancient_obs_1"))
        assertTrue(fusionResult.conflictingIds.contains("unverified_open_claim"))

        val edgeAfterConflict = mapDao.getEdge("ancient_blocked_edge")!!
        assertNotEquals("REPORTED_OPEN", edgeAfterConflict.routeStatus)
        assertEquals("CONFLICTED", edgeAfterConflict.routeStatus)

        // ONLY human-verified survey resolves the conflict
        val verifiedSurveyorObs = ObservationEntity(
            eventId = "official_surveyor_clearance",
            missionId = missionId,
            authorDeviceId = "lead-surveyor",
            authorSequence = 1L,
            eventType = "ROUTE_STATUS",
            title = "Official Surveyor Clearance",
            reportedStatus = "OPEN",
            verificationStatus = "VERIFIED",
            mapEdgeId = "ancient_blocked_edge",
            observedAtDevice = System.currentTimeMillis()
        )
        val finalResolution = fusionEngine.applyObservation(verifiedSurveyorObs)!!
        assertEquals("VERIFIED_OPEN", finalResolution.newStatus)
        assertFalse(finalResolution.isConflict)
        assertTrue(finalResolution.conflictingIds.contains("official_surveyor_clearance"))
    }
}
