package com.rescuemesh.ai.safety

import androidx.compose.ui.geometry.Offset
import com.rescuemesh.ai.data.dao.ChatDao
import com.rescuemesh.ai.data.dao.ObservationDao
import com.rescuemesh.ai.data.dao.SequenceDigest
import com.rescuemesh.ai.data.entity.ChatMessageEntity
import com.rescuemesh.ai.data.entity.DeliveryReceiptEntity
import com.rescuemesh.ai.data.entity.MapEdgeEntity
import com.rescuemesh.ai.data.entity.MapNodeEntity
import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.data.entity.OutboxEntity
import com.rescuemesh.ai.data.repository.ObservationRepository
import com.rescuemesh.ai.data.repository.SyncRepository
import com.rescuemesh.ai.fakes.FakeMapDao
import com.rescuemesh.ai.fakes.FakeMetricsDao
import com.rescuemesh.ai.fakes.FakeObservationDao
import com.rescuemesh.ai.fakes.FakeOutboxDao
import com.rescuemesh.ai.fakes.FakePeerDao
import com.rescuemesh.ai.intelligence.AutoFitViewportCalculator
import com.rescuemesh.ai.intelligence.CameraStateMachine
import com.rescuemesh.ai.intelligence.CameraTarget
import com.rescuemesh.ai.intelligence.MapFusionEngine
import com.rescuemesh.ai.intelligence.MarkerClusterEngine
import com.rescuemesh.ai.intelligence.PriorityScheduler
import com.rescuemesh.ai.intelligence.RoutePlanner
import com.rescuemesh.ai.location.LocationResult
import com.rescuemesh.ai.location.ProximityGuidance
import com.rescuemesh.ai.location.ProximityGuidanceEngine
import com.rescuemesh.ai.location.ProximityTrend
import com.rescuemesh.ai.maps.DownloadResult
import com.rescuemesh.ai.maps.OfflineMapManager
import com.rescuemesh.ai.maps.OfflineRegionModel
import com.rescuemesh.ai.maps.OfflineRegionStatus
import com.rescuemesh.ai.mesh.IncomingFileTransfer
import com.rescuemesh.ai.mesh.MeshState
import com.rescuemesh.ai.mesh.MeshTransport
import com.rescuemesh.ai.mesh.SyncCoordinator
import com.rescuemesh.ai.model.PeerInfo
import com.rescuemesh.ai.model.SyncMessage
import com.rescuemesh.ai.model.SyncObservation
import com.rescuemesh.ai.ui.screens.ProjectedSituationalPoint
import com.rescuemesh.ai.ui.screens.SituationalPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

/**
 * Authoritative Astra Gate 3 Security & Verification Regression Test Suite.
 *
 * Verifies confirmed Astra Gate 3 P0 invariants across mesh networking, mission authorization,
 * photographic evidence integrity, risk-avoiding route planning, movement trend filtering,
 * offline vector map quota enforcement with canvas fallback, and multi-hop store-and-forward relay.
 *
 * Mandate Coverage:
 * 1. [Astra P0-1 / G3-R01]: Transport ACKs and peer delivery receipts are strictly decoupled
 *    from responder incident acknowledgment. Incoming peer ACKs advance outbox delivery state
 *    (SENT_TO_PEERS / DELIVERED), but NEVER mutate incident/SOS state or claim responder assignment.
 *    All unauthenticated remote participants are labeled ROLE_UNVERIFIED.
 * 2. [Astra P0-2 / G3-R02]: Mission authorization MUST cover all outbound reads and file service.
 *    Evidence requests and headers for foreign mission observations are strictly rejected without
 *    dispatching files or mutating local state. Outbound digests and delta queries never leak records.
 * 3. [Astra P0-3 / G3-R03]: Photo byte integrity cannot grant human passage survey verification.
 *    Receiving and validating photo bytes sets evidence file status only, and MUST NEVER set
 *    observation.verificationStatus = "VERIFIED". Only human survey reports may set VERIFIED to
 *    prevent MapFusionEngine from falsely clearing blocked/conflicted passages.
 * 4. [Astra P0-4 / G3-R04]: Exclude blocked and conflicted passages by default (avoidConflicted = true).
 *    Never create synthetic walking edges from raw GPS fixes. Enforce prominent straight-line disclaimer
 *    and zero "safe route" claims.
 * 5. [Astra P0-5 / G3-R05]: Reject confident movement trends (CLOSING IN / MOVING AWAY) whenever the
 *    displacement between fixes is smaller than the GPS measurement uncertainty (delta_d < sigma_GPS).
 * 6. [Astra P0-6 / G3-R06]: Offline vector map storage quota enforcement and zero-dependency fallback
 *    to Situational Canvas when tiles are absent.
 * 7. [Astra P0-7 / G3-R07]: Deterministic Phone B store-and-forward relay from Phone A to Phone C.
 */
class AstraGate3SecurityAuditTest {

    private lateinit var observationDao: FakeObservationDao
    private lateinit var mapDao: FakeMapDao
    private lateinit var outboxDao: FakeOutboxDao
    private lateinit var metricsDao: FakeMetricsDao
    private lateinit var peerDao: FakePeerDao
    private lateinit var chatDao: AuditTestChatDao

    private lateinit var observationRepo: ObservationRepository
    private lateinit var syncRepo: SyncRepository
    private lateinit var scheduler: PriorityScheduler
    private lateinit var mapFusionEngine: MapFusionEngine
    private lateinit var transport: AuditTestMeshTransport
    private lateinit var syncCoordinator: SyncCoordinator

    private val localDeviceId = "node-phone-b"
    private val missionId = "MISSION_ASTRA_GATE3"
    private lateinit var evidenceTempDir: File

    @Before
    fun setUp() {
        evidenceTempDir = File(System.getProperty("java.io.tmpdir"), "astra_gate3_evidence_${System.currentTimeMillis()}")
        evidenceTempDir.mkdirs()

        observationDao = FakeObservationDao()
        mapDao = FakeMapDao()
        outboxDao = FakeOutboxDao()
        metricsDao = FakeMetricsDao()
        peerDao = FakePeerDao()
        chatDao = AuditTestChatDao()

        observationRepo = ObservationRepository(observationDao, outboxDao, localDeviceId, missionId)
        syncRepo = SyncRepository(metricsDao)
        scheduler = PriorityScheduler(observationDao, mapDao, outboxDao, chatDao)
        mapFusionEngine = MapFusionEngine(mapDao, observationDao)
        transport = AuditTestMeshTransport()

        syncCoordinator = SyncCoordinator(
            transport = transport,
            observationDao = observationDao,
            outboxDao = outboxDao,
            observationRepo = observationRepo,
            syncRepo = syncRepo,
            scheduler = scheduler,
            mapFusionEngine = mapFusionEngine,
            deviceId = localDeviceId,
            missionId = missionId,
            evidenceDir = evidenceTempDir,
            chatDao = chatDao
        )
    }

    // =========================================================================
    // 1. [Astra P0-1 / G3-R01]: Transport ACK Decoupling & Role Sanitization
    // =========================================================================

    @Test
    fun testTransportAckDecoupledFromIncidentAndDoesNotMutateObservation() = runBlocking {
        // Step 1: Author a critical local SOS incident observation
        val sosEventId = "sos-incident-101"
        val originalObs = ObservationEntity(
            eventId = sosEventId,
            missionId = missionId,
            authorDeviceId = localDeviceId,
            authorSequence = 1L,
            eventType = "SOS",
            title = "Victim Trapped in Collapsed Stairwell",
            description = "Third floor stairwell blocked by debris",
            reportedStatus = "OPEN",
            verificationStatus = "UNVERIFIED",
            observedAtDevice = 1000L
        )
        observationDao.insert(originalObs)

        // Queue in outbox for a nearby peer
        val responderDeviceId = "peer-responder-alpha"
        transport.addPeer(responderDeviceId, "endpoint-responder-alpha")
        outboxDao.insert(
            OutboxEntity(
                eventId = sosEventId,
                targetPeerId = responderDeviceId,
                priority = 1.0f,
                status = "PENDING"
            )
        )

        // Step 2: Peer sends transport-level EventAck
        val ack = SyncMessage.EventAck(
            deviceId = responderDeviceId,
            acknowledgedEventIds = listOf(sosEventId)
        )
        syncCoordinator.handleEventAck(ack)

        // Step 3: Assert delivery receipt was recorded
        assertTrue(
            "OutboxDao must record delivery receipt for physical delivery",
            outboxDao.isDeliveredTo(sosEventId, responderDeviceId)
        )

        // Step 4: Strict Invariant Check: The incident/SOS state MUST NEVER be mutated
        val afterAckObs = observationDao.getById(sosEventId)
        assertNotNull("Observation must still exist in Room database", afterAckObs)
        assertEquals("Incident reportedStatus must remain OPEN", "OPEN", afterAckObs?.reportedStatus)
        assertEquals("Incident verificationStatus must remain UNVERIFIED", "UNVERIFIED", afterAckObs?.verificationStatus)
        assertEquals("Incident title must remain unchanged", "Victim Trapped in Collapsed Stairwell", afterAckObs?.title)
        assertEquals("Incident description must remain unchanged", "Third floor stairwell blocked by debris", afterAckObs?.description)
        assertFalse(
            "Incident must never be marked RESOLVED or ACKNOWLEDGED merely by transport delivery",
            afterAckObs?.reportedStatus in listOf("RESOLVED", "ACKNOWLEDGED", "ASSIGNED")
        )
    }

    @Test
    fun testChatAckAdvancesDeliveryStateOnlyAndSanitizesRemoteRoles() = runBlocking {
        // Part A: Chat ACK advances delivery state to SENT_TO_PEERS without mutating incident
        val linkedSosId = "sos-incident-202"
        observationDao.insert(
            ObservationEntity(
                eventId = linkedSosId,
                missionId = missionId,
                authorDeviceId = localDeviceId,
                authorSequence = 1L,
                eventType = "SOS",
                title = "Emergency Medical Evacuation",
                reportedStatus = "OPEN",
                verificationStatus = "UNVERIFIED"
            )
        )

        val chatMessageId = "chat-req-001"
        val chatEntity = ChatMessageEntity(
            messageId = chatMessageId,
            missionId = missionId,
            senderId = localDeviceId,
            senderName = "Station B",
            text = "Requesting paramedic escort",
            timestamp = 2000L,
            status = ChatMessageEntity.STATUS_SENDING,
            ackPeerCount = 0,
            isLocal = true,
            linkedEventId = linkedSosId
        )
        chatDao.insert(chatEntity)
        outboxDao.insert(
            OutboxEntity(
                eventId = chatMessageId,
                targetPeerId = "peer-paramedic",
                priority = 0.9f,
                status = "PENDING"
            )
        )

        // Handle ChatAck from peer
        val chatAck = SyncMessage.ChatAck(
            deviceId = "peer-paramedic",
            acknowledgedMessageIds = listOf(chatMessageId)
        )
        syncCoordinator.handleChatAck(chatAck)

        val updatedChat = chatDao.getMessage(chatMessageId)
        assertNotNull(updatedChat)
        assertEquals(ChatMessageEntity.STATUS_SENT_TO_PEERS, updatedChat?.status)
        assertEquals(1, updatedChat?.ackPeerCount)

        // Replayed ACK must NOT inflate peer ACK count
        syncCoordinator.handleChatAck(chatAck)
        assertEquals("Duplicate ACK must not inflate peer ACK count", 1, chatDao.getMessage(chatMessageId)?.ackPeerCount)

        // Linked SOS incident remains intact
        assertEquals("OPEN", observationDao.getById(linkedSosId)?.reportedStatus)

        // Part B: Remote participants claiming privileged roles are sanitized to ROLE_UNVERIFIED
        val spoofedRemoteMsg = SyncMessage.ChatMessage(
            messageId = "chat-spoofed-001",
            missionId = missionId,
            senderDeviceId = "unauthenticated-peer",
            senderName = "Self-Declared Commander",
            text = "Directing all units to Sector 9",
            timestamp = 3000L,
            senderRole = ChatMessageEntity.ROLE_VERIFIED_RESPONDER
        )
        syncCoordinator.handleChatMessage("endpoint-unauth", spoofedRemoteMsg)

        val storedRemoteMsg = chatDao.getMessage("chat-spoofed-001")
        assertNotNull(storedRemoteMsg)
        assertEquals(
            "Unauthenticated remote participants must strictly be sanitized to ROLE_UNVERIFIED",
            ChatMessageEntity.ROLE_UNVERIFIED,
            storedRemoteMsg?.senderRole
        )
    }

    // =========================================================================
    // 2. [Astra P0-2 / G3-R02]: Mission Authorization on Outbound Reads & Service
    // =========================================================================

    @Test
    fun testMissionAuthorizationRejectsForeignMissionEvidenceRequestAndHeader() = runBlocking {
        transport.addPeer("peer-delta", "endpoint-delta")

        // Seed an observation belonging to a foreign mission with attached photo file
        val foreignFile = File(evidenceTempDir, "foreign_sample.jpg")
        foreignFile.writeBytes("CONFIDENTIAL_FOREIGN_DATA".toByteArray())
        val foreignHash = computeSha256(foreignFile)

        val foreignObs = ObservationEntity(
            eventId = "obs-foreign-private-88",
            missionId = "FOREIGN_MISSION_CLASSIFIED",
            authorDeviceId = "remote-node-z",
            authorSequence = 12L,
            eventType = "HAZARD",
            title = "Classified Foreign Hazard",
            evidencePath = foreignFile.absolutePath,
            evidenceHash = foreignHash,
            observedAtDevice = 1000L
        )
        observationDao.insert(foreignObs)

        // Case 1: Peer on active session requests evidence for foreign mission observation
        val spoofedRequest = SyncMessage.EvidenceRequest(
            missionId = missionId, // Spoofed request claiming active mission
            eventId = "obs-foreign-private-88",
            evidenceHash = foreignHash
        )
        syncCoordinator.handleEvidenceRequest("endpoint-delta", spoofedRequest)

        // Verify NO EvidenceHeader and NO file payload was dispatched
        val headersSent = transport.sentMessages.filter { it.second is SyncMessage.EvidenceHeader }
        assertEquals("Must NOT serve EvidenceHeader for foreign mission observation", 0, headersSent.size)
        assertEquals("Must NOT send file payload for foreign mission observation", 0, transport.sentFiles.size)

        // Case 2: Peer sends EvidenceHeader belonging to a foreign mission
        val foreignHeader = SyncMessage.EvidenceHeader(
            missionId = "FOREIGN_MISSION_CLASSIFIED",
            eventId = "obs-foreign-private-88",
            evidenceHash = foreignHash,
            payloadId = 9999L,
            fileSizeBytes = foreignFile.length()
        )
        syncCoordinator.handleEvidenceHeader("endpoint-delta", foreignHeader)

        // Verify foreign header was rejected (not buffered or processed)
        assertEquals(0, transport.sentMessages.filter { it.second is SyncMessage.EvidenceAck }.size)
    }

    @Test
    fun testMissionScopedDigestAndDeltaQueriesNeverLeakForeignObservations() = runBlocking {
        // Seed active mission observations
        val activeObs = ObservationEntity(
            eventId = "obs-active-001",
            missionId = missionId,
            authorDeviceId = localDeviceId,
            authorSequence = 1L,
            eventType = "NOTE",
            title = "Active Mission Note"
        )
        // Seed foreign mission observations
        val foreignObs = ObservationEntity(
            eventId = "obs-foreign-002",
            missionId = "MISSION_OTHER_SECRET",
            authorDeviceId = localDeviceId,
            authorSequence = 2L,
            eventType = "NOTE",
            title = "Foreign Mission Secret"
        )
        observationDao.insert(activeObs)
        observationDao.insert(foreignObs)

        transport.addPeer("peer-charlie", "endpoint-charlie")
        transport.sentMessages.clear()

        // Handle incoming digest for active session
        val digest = SyncMessage.StateDigest(
            deviceId = "peer-charlie",
            sequences = mapOf(localDeviceId to 0L)
        )
        syncCoordinator.handleDigest("endpoint-charlie", digest)

        // Process pending outbox
        syncCoordinator.processPendingOutbox()

        // Verify that only active mission observations are transmitted
        val sentBatches = transport.sentMessages
            .map { it.second }
            .filterIsInstance<SyncMessage.EventBatch>()

        for (batch in sentBatches) {
            for (obs in batch.events) {
                assertEquals(
                    "Outbound event delta must belong exclusively to active mission",
                    missionId,
                    obs.missionId
                )
                assertNotEquals("Foreign mission event must NEVER escape", "obs-foreign-002", obs.eventId)
                assertFalse("Foreign title must NEVER leak", obs.title.contains("Foreign"))
            }
        }
    }

    // =========================================================================
    // 3. [Astra P0-3 / G3-R03]: Photo Byte Integrity vs Human Passage Verification
    // =========================================================================

    @Test
    fun testPhotoByteIntegrityCannotClearBlockedOrConflictedPassageInMapFusion() = runBlocking {
        val edgeId = "edge-stairwell-collapse"
        val mission = missionId

        // Step 1: Initial topological state is REPORTED_BLOCKED
        val blockedEdge = MapEdgeEntity(
            edgeId = edgeId,
            missionId = mission,
            fromNodeId = "node_floor2",
            toNodeId = "node_floor3",
            distance = 15f,
            routeStatus = "REPORTED_BLOCKED"
        )
        mapDao.insertEdge(blockedEdge)

        // Step 2: Civilian bystander takes a photo and submits an unverified report claiming OPEN
        val photoFile = File(evidenceTempDir, "stairwell_evidence.jpg")
        photoFile.writeBytes("CORRIDOR_IMAGE_BYTES_12345".toByteArray())
        val photoHash = computeSha256(photoFile)

        val photoObservation = ObservationEntity(
            eventId = "obs-photo-stairwell",
            missionId = mission,
            authorDeviceId = "civilian-phone-x",
            authorSequence = 1L,
            eventType = "PHOTO",
            title = "Photo of stairwell",
            reportedStatus = "OPEN",
            mapEdgeId = edgeId,
            sourceType = "HUMAN_OBSERVATION",
            evidencePath = photoFile.absolutePath,
            evidenceHash = photoHash,
            verificationStatus = "UNVERIFIED" // Photo integrity alone MUST NOT set this to VERIFIED
        )
        observationDao.insert(photoObservation)

        // Step 3: Apply the observation with valid photo bytes to MapFusionEngine
        val fusionResult = mapFusionEngine.applyObservation(photoObservation)
        assertNotNull(fusionResult)

        // Step 4: Strict Invariant: Valid photo bytes for an unverified report must NEVER clear blocked passage to VERIFIED_OPEN
        val currentEdge = mapDao.getEdge(edgeId)
        assertNotNull(currentEdge)
        assertNotEquals(
            "Valid photo bytes must NEVER upgrade passage to VERIFIED_OPEN without human survey verification",
            "VERIFIED_OPEN",
            currentEdge?.routeStatus
        )
        assertEquals(
            "Conflicting unverified open report on a blocked edge must result in CONFLICTED",
            "CONFLICTED",
            currentEdge?.routeStatus
        )

        // Step 5: Contrast: ONLY an explicit human survey report with verificationStatus = VERIFIED resolves passage
        val officialSurveyObs = ObservationEntity(
            eventId = "survey-verified-engineer",
            missionId = mission,
            authorDeviceId = "structural-engineer-alpha",
            authorSequence = 2L,
            eventType = "ROUTE_STATUS",
            title = "Official Structural Survey Clearance",
            reportedStatus = "OPEN",
            mapEdgeId = edgeId,
            sourceType = "HUMAN_OBSERVATION",
            verificationStatus = "VERIFIED"
        )
        val surveyResult = mapFusionEngine.applyObservation(officialSurveyObs)
        assertNotNull(surveyResult)
        assertEquals(
            "Only authenticated human survey report may resolve passage to VERIFIED_OPEN",
            "VERIFIED_OPEN",
            mapDao.getEdge(edgeId)?.routeStatus
        )
    }

    // =========================================================================
    // 4. [Astra P0-4 / G3-R04]: Exclude Blocked & Conflicted Passages & Zero Synthetic Edges
    // =========================================================================

    @Test
    fun testRoutePlannerExcludesBlockedAndConflictedPassagesByDefault() = runBlocking {
        val mId = missionId
        val startNode = MapNodeEntity("node_start", mId, "Base Station", latitude = 12.0, longitude = 77.0)
        val blockedMid = MapNodeEntity("node_blocked", mId, "Blocked Path", latitude = 12.01, longitude = 77.01)
        val conflictedMid = MapNodeEntity("node_conflicted", mId, "Conflicted Path", latitude = 12.02, longitude = 77.02)
        val clearMid = MapNodeEntity("node_clear", mId, "Clear Detour", latitude = 12.03, longitude = 77.03)
        val destNode = MapNodeEntity("node_dest", mId, "Triage Area", latitude = 12.04, longitude = 77.04)

        mapDao.insertNodes(listOf(startNode, blockedMid, conflictedMid, clearMid, destNode))

        // Path 1 (Direct 20m): via node_blocked (VERIFIED_BLOCKED)
        mapDao.insertEdges(listOf(
            MapEdgeEntity("e_b1", mId, "node_start", "node_blocked", 10f, "REPORTED_OPEN"),
            MapEdgeEntity("e_b2", mId, "node_blocked", "node_dest", 10f, "VERIFIED_BLOCKED")
        ))
        // Path 2 (Direct 20m): via node_conflicted (CONFLICTED)
        mapDao.insertEdges(listOf(
            MapEdgeEntity("e_c1", mId, "node_start", "node_conflicted", 10f, "REPORTED_OPEN"),
            MapEdgeEntity("e_c2", mId, "node_conflicted", "node_dest", 10f, "CONFLICTED")
        ))
        // Path 3 (Detour 60m): via node_clear (REPORTED_OPEN / verified safe detour)
        mapDao.insertEdges(listOf(
            MapEdgeEntity("e_d1", mId, "node_start", "node_clear", 30f, "REPORTED_OPEN"),
            MapEdgeEntity("e_d2", mId, "node_clear", "node_dest", 30f, "REPORTED_OPEN")
        ))

        val planner = RoutePlanner(mapDao)

        // Default safety configuration: avoidBlocked = true, avoidConflicted = true
        val safeRoute = planner.findRoute(
            fromNodeId = "node_start",
            toNodeId = "node_dest",
            missionId = mId,
            avoidBlocked = true,
            avoidConflicted = true
        )

        assertTrue("Safe route should be found via detour", safeRoute.isComplete)
        assertEquals(listOf("node_start", "node_clear", "node_dest"), safeRoute.path)
        assertFalse("Route must not traverse blocked edge", safeRoute.edgesUsed.contains("e_b2"))
        assertFalse("Route must not traverse conflicted edge", safeRoute.edgesUsed.contains("e_c2"))

        // When detour is unavailable, route planner must fail safe rather than route through danger
        val noDetourMapDao = FakeMapDao()
        noDetourMapDao.insertNodes(listOf(startNode, blockedMid, conflictedMid, destNode))
        noDetourMapDao.insertEdges(listOf(
            MapEdgeEntity("e_b1", mId, "node_start", "node_blocked", 10f, "REPORTED_OPEN"),
            MapEdgeEntity("e_b2", mId, "node_blocked", "node_dest", 10f, "VERIFIED_BLOCKED"),
            MapEdgeEntity("e_c1", mId, "node_start", "node_conflicted", 10f, "REPORTED_OPEN"),
            MapEdgeEntity("e_c2", mId, "node_conflicted", "node_dest", 10f, "CONFLICTED")
        ))

        val blockedRoute = RoutePlanner(noDetourMapDao).findRoute(
            fromNodeId = "node_start",
            toNodeId = "node_dest",
            missionId = mId,
            avoidBlocked = true,
            avoidConflicted = true
        )
        assertFalse("When only blocked and conflicted passages exist, planner must reject route", blockedRoute.isComplete)
        assertTrue("No path should be returned", blockedRoute.path.isEmpty())
    }

    @Test
    fun testZeroSyntheticWalkingEdgesFromRawGpsFixes() = runBlocking {
        val initialNodes = mapDao.getAllNodes(missionId).size
        val initialEdges = mapDao.getAllEdges(missionId).size

        // Feed a flood of raw GPS points into viewport and clustering engines
        val rawPoints = (1..50).map { i ->
            SituationalPoint(
                id = "gps_fix_$i",
                latitude = 12.9716 + (i * 0.0002),
                longitude = 77.5946 + (i * 0.0002),
                label = "Raw GPS Track #$i",
                type = "CIVILIAN_GPS"
            )
        }

        // 1. Viewport calculator
        val viewport = AutoFitViewportCalculator.computeViewportFromPoints(rawPoints)
        assertTrue(viewport.radiusMeters > 0)

        // 2. Marker cluster engine
        val projected = rawPoints.mapIndexed { idx, pt ->
            ProjectedSituationalPoint(
                point = pt,
                canvasOffset = Offset(50f + idx * 5f, 50f + idx * 5f),
                distanceMeters = 30f + idx,
                bearingDegrees = 45f,
                isWithinBounds = true,
                accuracyRadiusPx = 10f
            )
        }
        val clusters = MarkerClusterEngine.cluster(projected)
        assertTrue(clusters.isNotEmpty())

        // 3. Camera state machine
        val camera = CameraStateMachine()
        camera.onSelectTarget(CameraTarget("gps_fix_1", 12.9718, 77.5948))
        camera.onUserLocationUpdated(12.9716, 77.5946)

        // Assert zero mutation to MapDao
        assertEquals(
            "Topological nodes must never be synthesized from raw GPS fixes",
            initialNodes,
            mapDao.getAllNodes(missionId).size
        )
        assertEquals(
            "Walking edges must never be synthesized from raw GPS fixes",
            initialEdges,
            mapDao.getAllEdges(missionId).size
        )
    }

    @Test
    fun testProminentStraightLineSafetyDisclaimerEnforcedAndNoSafeRouteClaims() {
        val expectedDisclaimer =
            "Straight-line proximity only. No surveyed walking route available. Watch for unmapped hazards."

        assertEquals(expectedDisclaimer, ProximityGuidance.SAFETY_WARNING_TEXT)
        assertEquals(expectedDisclaimer, ProximityGuidanceEngine.SAFETY_WARNING_TEXT)
        assertEquals(expectedDisclaimer, MarkerClusterEngine.SAFETY_DISCLAIMER)
        assertEquals(expectedDisclaimer, AutoFitViewportCalculator.SAFETY_DISCLAIMER)

        val guidance = ProximityGuidanceEngine.computeGuidance(
            fixes = listOf(
                LocationResult(12.9716, 77.5946, 5.0f, provider = "gps", timestamp = 1000L)
            ),
            targetLat = 12.9726,
            targetLon = 77.5946
        )

        assertEquals(expectedDisclaimer, guidance.safetyWarning)
        assertFalse(
            "Guidance warning must NEVER describe routes as safe",
            guidance.safetyWarning.contains("safe route", ignoreCase = true)
        )
    }

    // =========================================================================
    // 5. [Astra P0-5 / G3-R05]: Reject Confident Movement Trends When delta_d < sigma_GPS
    // =========================================================================

    enum class MovementTrend {
        CLOSING_IN,
        MOVING_AWAY,
        TREND_UNCERTAIN
    }

    /**
     * Authoritative Astra Gate 3 P0-5 Movement Trend Evaluator:
     *
     * Invariant:
     * Reject confident movement trends (CLOSING IN / MOVING AWAY) whenever the displacement
     * between fixes is smaller than the GPS measurement uncertainty (delta_d < sigma_GPS).
     */
    private fun evaluateMovementTrend(d1: Double, sigma1: Double, d2: Double, sigma2: Double): MovementTrend {
        val deltaD = abs(d2 - d1)
        val sigmaGps = max(sigma1, sigma2)

        if (deltaD < sigmaGps) {
            return MovementTrend.TREND_UNCERTAIN
        }

        return if (d2 < d1) {
            MovementTrend.CLOSING_IN
        } else {
            MovementTrend.MOVING_AWAY
        }
    }

    @Test
    fun testMovementTrendRejectsConfidentClaimsWhenDisplacementLessThanGpsUncertainty() {
        // Vector M01 from Astra GPS_AND_PROXIMITY_TEST_VECTORS.md:
        // Distances: 100m -> 95m, with 30m GPS uncertainty
        // delta_d = 5m < sigma_GPS = 30m => MUST BE TREND_UNCERTAIN
        val trendM01 = evaluateMovementTrend(d1 = 100.0, sigma1 = 30.0, d2 = 95.0, sigma2 = 30.0)
        assertEquals(
            "Small displacement (5m) within 30m GPS uncertainty must evaluate to TREND_UNCERTAIN",
            MovementTrend.TREND_UNCERTAIN,
            trendM01
        )

        // Vector M04: 5m displacement in 1s, accuracy 30m
        val trendM04 = evaluateMovementTrend(d1 = 50.0, sigma1 = 30.0, d2 = 55.0, sigma2 = 30.0)
        assertEquals(
            "5m displacement within 30m accuracy must evaluate to TREND_UNCERTAIN",
            MovementTrend.TREND_UNCERTAIN,
            trendM04
        )

        // Multi-sample jitter sequence: 100m, 95m, 102m, 97m with 30m accuracy
        val jitterSequence = listOf(100.0, 95.0, 102.0, 97.0)
        for (i in 0 until jitterSequence.size - 1) {
            val stepTrend = evaluateMovementTrend(
                d1 = jitterSequence[i],
                sigma1 = 30.0,
                d2 = jitterSequence[i + 1],
                sigma2 = 30.0
            )
            assertEquals(
                "Jitter step $i -> ${i + 1} must be TREND_UNCERTAIN to prevent UI oscillation",
                MovementTrend.TREND_UNCERTAIN,
                stepTrend
            )
        }

        // Vector M02: Confident approaching trend (delta_d >= sigma_GPS)
        // Distances: 200m -> 150m, with 3m high-accuracy GPS
        val trendM02 = evaluateMovementTrend(d1 = 200.0, sigma1 = 3.0, d2 = 150.0, sigma2 = 3.0)
        assertEquals(
            "Significant displacement (50m) exceeding 3m GPS accuracy correctly evaluates to CLOSING_IN",
            MovementTrend.CLOSING_IN,
            trendM02
        )

        // Vector M03: Confident moving away trend (delta_d >= sigma_GPS)
        val trendM03 = evaluateMovementTrend(d1 = 100.0, sigma1 = 3.0, d2 = 150.0, sigma2 = 3.0)
        assertEquals(
            "Significant displacement (50m) exceeding 3m GPS accuracy correctly evaluates to MOVING_AWAY",
            MovementTrend.MOVING_AWAY,
            trendM03
        )
    }

    @Test
    fun testProximityGuidanceEngineRejectsConfidentTrendsWhenDisplacementLessThanSigmaGps() {
        val targetLat = 12.9726
        val targetLon = 77.5946

        // Vector M01 from Astra GPS_AND_PROXIMITY_TEST_VECTORS.md:
        // User moves ~5m, but GPS uncertainty is 30m -> MUST BE STATIONARY_APPROXIMATE
        val fixesM01 = listOf(
            LocationResult(12.9716, 77.5946, accuracy = 30.0f, provider = "gps", timestamp = 1000L),
            LocationResult(12.971645, 77.5946, accuracy = 30.0f, provider = "gps", timestamp = 6000L)
        )
        val (trendM01, _) = ProximityGuidanceEngine.classifyTrend(fixesM01, targetLat, targetLon)
        assertEquals(
            "Displacement within GPS uncertainty must evaluate to STATIONARY_APPROXIMATE",
            ProximityTrend.STATIONARY_APPROXIMATE,
            trendM01
        )

        // Vector M02: User moves ~55m closing in with high accuracy (3m GPS accuracy)
        val fixesM02 = listOf(
            LocationResult(12.9706, 77.5946, accuracy = 3.0f, provider = "gps", timestamp = 1000L),
            LocationResult(12.9711, 77.5946, accuracy = 3.0f, provider = "gps", timestamp = 6000L)
        )
        val (trendM02, deltaM02) = ProximityGuidanceEngine.classifyTrend(fixesM02, targetLat, targetLon)
        assertEquals(
            "Significant movement exceeding GPS accuracy towards target evaluates to CLOSING_IN",
            ProximityTrend.CLOSING_IN,
            trendM02
        )
        assertTrue(deltaM02 < 0.0)

        // Vector M03: User moves ~55m away from target with 3m accuracy
        val fixesM03 = listOf(
            LocationResult(12.9711, 77.5946, accuracy = 3.0f, provider = "gps", timestamp = 1000L),
            LocationResult(12.9706, 77.5946, accuracy = 3.0f, provider = "gps", timestamp = 6000L)
        )
        val (trendM03, deltaM03) = ProximityGuidanceEngine.classifyTrend(fixesM03, targetLat, targetLon)
        assertEquals(
            "Significant movement exceeding GPS accuracy away from target evaluates to MOVING_AWAY",
            ProximityTrend.MOVING_AWAY,
            trendM03
        )
        assertTrue(deltaM03 > 0.0)
    }

    // =========================================================================
    // 6. [Astra P0-6 / G3-R06]: Offline Map Storage Quotas & Situational Canvas Fallback
    // =========================================================================

    @Test
    fun testOfflineMapStorageQuotasAndGracefulFallbackToSituationalCanvas() {
        val mapDir = File(System.getProperty("java.io.tmpdir"), "astra_offline_maps_${System.currentTimeMillis()}")
        mapDir.mkdirs()

        try {
            // Setup OfflineMapManager with a strict 100 MB quota
            val maxQuotaBytes = 100L * 1024L * 1024L
            val mapManager = OfflineMapManager(
                baseDir = mapDir,
                workManager = null,
                initialMaxQuotaBytes = maxQuotaBytes
            )

            // Part A: Zero-dependency fallback to Situational Canvas when tiles are absent
            val unmappedLat = 13.0827 // Chennai coordinate
            val unmappedLon = 80.2707
            assertFalse(
                "Must report no offline map available for unmapped coordinates",
                mapManager.hasOfflineMapFor(unmappedLat, unmappedLon)
            )
            assertTrue(
                "UI must gracefully fall back to Situational Canvas when vector tiles are absent",
                mapManager.shouldFallbackToCanvas(unmappedLat, unmappedLon)
            )

            // Part B: Storage quota enforcement on download requests
            val normalRegion = OfflineRegionModel(
                regionId = "test_sector_alpha",
                name = "Tactical Sector Alpha",
                minLat = 12.90, maxLat = 13.00,
                minLon = 77.50, maxLon = 77.60,
                fileSizeBytes = 10L * 1024L * 1024L,
                fileName = "test_sector_alpha.mbtiles"
            )
            val oversizedRegion = OfflineRegionModel(
                regionId = "test_sector_oversized",
                name = "Oversized Region",
                minLat = 10.0, maxLat = 15.0,
                minLon = 75.0, maxLon = 80.0,
                fileSizeBytes = 150L * 1024L * 1024L,
                fileName = "test_sector_oversized.mbtiles"
            )
            mapManager.registerRegion(normalRegion)
            mapManager.registerRegion(oversizedRegion)

            // Oversized region exceeds quota (150MB > 100MB quota)
            val quotaResult = mapManager.enqueueDownload("test_sector_oversized")
            assertTrue(
                "Oversized region download must be rejected with QuotaExceeded",
                quotaResult is DownloadResult.QuotaExceeded
            )

            // Part C: When a package is downloaded and verified on disk, vector rendering is ready
            val alphaTileFile = File(mapManager.offlineMapsDir, normalRegion.fileName)
            alphaTileFile.writeBytes(ByteArray(1024) { 0x55 }) // Seed valid physical tile file

            mapManager.scanAndSyncStorage()

            val downloadedRegion = mapManager.getRegion("test_sector_alpha")
            assertNotNull(downloadedRegion)
            assertEquals(OfflineRegionStatus.DOWNLOADED, downloadedRegion?.status)
            assertTrue(
                "Coordinate inside downloaded region must report offline map present",
                mapManager.hasOfflineMapFor(12.95, 77.55)
            )
            assertFalse(
                "Coordinate inside downloaded region must NOT fall back to canvas",
                mapManager.shouldFallbackToCanvas(12.95, 77.55)
            )

            // Storage accounting
            assertTrue("Storage used must reflect physical file size", mapManager.getTotalStorageUsedBytes() > 0L)
            assertTrue("Remaining quota must decrease", mapManager.getRemainingQuotaBytes() < maxQuotaBytes)

            // Deletion frees quota and restores canvas fallback
            val deleted = mapManager.deleteRegion("test_sector_alpha")
            assertTrue("Deletion must succeed", deleted)
            assertFalse("Tile file must be deleted", alphaTileFile.exists())
            assertTrue(
                "After package deletion, must fall back to Situational Canvas",
                mapManager.shouldFallbackToCanvas(12.95, 77.55)
            )
        } finally {
            mapDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 7. [Astra P0-7 / G3-R07]: Deterministic Phone B Store-and-Forward Relay
    // =========================================================================

    @Test
    fun testDeterministicStoreAndForwardRelayFromPhoneAToPhoneCViaPhoneB() = runBlocking {
        // Step 1: Encounter 1 (Phone A <-> Phone B):
        // Phone A connects to Phone B. Phone C is offline/disconnected.
        transport.addPeer("phone-a", "endpoint-a")

        val phoneASos = SyncMessage.EventBatch(
            senderDeviceId = "phone-a",
            events = listOf(
                SyncObservation(
                    eventId = "sos-saf-relay-001",
                    missionId = missionId,
                    authorDeviceId = "phone-a",
                    authorSequence = 1L,
                    eventType = "SOS",
                    title = "Building Structural Breach at Sector 7",
                    description = "Three casualties trapped",
                    reportedStatus = "OPEN",
                    relayHopsRemaining = 3,
                    observedAtDevice = 10000L
                )
            )
        )

        val ackToA = syncCoordinator.handleEventBatch("endpoint-a", phoneASos)
        assertEquals(listOf("sos-saf-relay-001"), ackToA.acknowledgedEventIds)

        // Verify Phone B saved the observation locally in durable storage
        val storedAtB = observationDao.getById("sos-saf-relay-001")
        assertNotNull("Phone B must persist relayed SOS in local Room DB", storedAtB)
        assertEquals("phone-a", storedAtB?.authorDeviceId)
        assertEquals("Building Structural Breach at Sector 7", storedAtB?.title)

        // Step 2: Physical partition phase
        // Phone A disconnects from Phone B
        transport.removePeer("phone-a")
        assertTrue("Phone A is disconnected", transport.connectedPeers.value.isEmpty())

        // Step 3: Encounter 2 (Phone B <-> Phone C):
        // Phone C connects to Phone B while Phone A remains offline
        transport.addPeer("phone-c", "endpoint-c")

        // Queue in outbox for Phone C with decremented hops
        outboxDao.insert(
            OutboxEntity(
                eventId = "sos-saf-relay-001",
                targetPeerId = "phone-c",
                priority = scheduler.scoreObservation(storedAtB!!),
                maxHops = storedAtB.relayHopsRemaining - 1, // 3 - 1 = 2
                status = "PENDING"
            )
        )

        // Phone B transmits pending outbox to Phone C
        val transmittedCount = syncCoordinator.processPendingOutbox()
        assertEquals("Phone B must transmit 1 store-and-forward message to Phone C", 1, transmittedCount)

        // Verify message received at Phone C's endpoint
        val sentToC = transport.sentMessages.filter { it.first == "endpoint-c" && it.second is SyncMessage.EventBatch }
        assertEquals(1, sentToC.size)
        val relayedBatch = sentToC[0].second as SyncMessage.EventBatch
        assertEquals(localDeviceId, relayedBatch.senderDeviceId) // Sender is relaying node B
        assertEquals(1, relayedBatch.events.size)

        val relayedObs = relayedBatch.events[0]
        assertEquals("sos-saf-relay-001", relayedObs.eventId)
        assertEquals("phone-a", relayedObs.authorDeviceId) // Original author preserved
        assertEquals("Building Structural Breach at Sector 7", relayedObs.title)
        assertTrue("Relayed observation must have positive relay hops", relayedObs.relayHopsRemaining > 0)

        // Invariant: Event batch payload must NEVER be echoed back to author Phone A
        val echoedToA = transport.sentMessages.filter { it.first == "endpoint-a" && it.second is SyncMessage.EventBatch }
        assertEquals("Store-and-forward relay must never echo batch back to author Phone A", 0, echoedToA.size)

        // Phone C sends ACK to Phone B, confirming receipt
        val ackFromC = SyncMessage.EventAck(
            deviceId = "phone-c",
            acknowledgedEventIds = listOf("sos-saf-relay-001")
        )
        syncCoordinator.handleEventAck(ackFromC)
        assertTrue(
            "Phone B must record delivery receipt for Phone C",
            outboxDao.isDeliveredTo("sos-saf-relay-001", "phone-c")
        )
    }

    @Test
    fun testDeterministicChatStoreAndForwardRelayWithDecrementedHops() = runBlocking {
        // Step 1: Phone A connects to Phone B and sends ChatMessage
        transport.addPeer("phone-a", "endpoint-a")

        val phoneAChat = SyncMessage.ChatMessage(
            messageId = "chat-saf-001",
            missionId = missionId,
            senderDeviceId = "phone-a",
            senderName = "Team Alpha Lead",
            text = "Sector 4 clear, advancing to Sector 5",
            timestamp = 5000L,
            relayHopsRemaining = 3
        )
        val ackToA = syncCoordinator.handleChatMessage("endpoint-a", phoneAChat)
        assertEquals(listOf("chat-saf-001"), ackToA.acknowledgedMessageIds)

        // Verify Phone B persisted chat message
        val storedChat = chatDao.getMessage("chat-saf-001")
        assertNotNull(storedChat)
        assertEquals("phone-a", storedChat?.senderId)
        assertEquals(ChatMessageEntity.ROLE_UNVERIFIED, storedChat?.senderRole)

        // Step 2: Phone A disconnects (radio partition)
        transport.removePeer("phone-a")
        assertTrue("Phone A is disconnected", transport.connectedPeers.value.isEmpty())

        // Step 3: Phone C connects to Phone B
        transport.addPeer("phone-c", "endpoint-c")

        // Queue in outbox for Phone C with decremented maxHops
        outboxDao.insert(
            OutboxEntity(
                eventId = "chat-saf-001",
                targetPeerId = "phone-c",
                priority = scheduler.scoreChatMessage(storedChat!!),
                maxHops = storedChat.relayHopsRemaining - 1, // 3 - 1 = 2
                status = "PENDING"
            )
        )

        // Phone B transmits pending outbox
        val transmitted = syncCoordinator.processPendingOutbox()
        assertEquals(1, transmitted)

        // Verify message received at Phone C with decremented hops on wire
        val sentToC = transport.sentMessages.filter { it.first == "endpoint-c" && it.second is SyncMessage.ChatMessage }
        assertEquals(1, sentToC.size)
        val relayedChat = sentToC[0].second as SyncMessage.ChatMessage
        assertEquals("chat-saf-001", relayedChat.messageId)
        assertEquals("phone-a", relayedChat.senderDeviceId) // Original author preserved
        assertEquals(
            "Relay hops remaining must be decremented on wire for chat relay (3 -> 2)",
            2,
            relayedChat.relayHopsRemaining
        )

        // Phone C sends ChatAck back to Phone B
        val chatAckFromC = SyncMessage.ChatAck(
            deviceId = "phone-c",
            acknowledgedMessageIds = listOf("chat-saf-001")
        )
        syncCoordinator.handleChatAck(chatAckFromC)
        assertTrue(
            "Phone B must record delivery receipt for Phone C",
            outboxDao.isDeliveredTo("chat-saf-001", "phone-c")
        )
        val updatedChatAfterAck = chatDao.getMessage("chat-saf-001")
        assertEquals(1, updatedChatAfterAck?.ackPeerCount)
        assertEquals(
            "Remote received chat preserves STATUS_RECEIVED while delivery receipts increment",
            ChatMessageEntity.STATUS_RECEIVED,
            updatedChatAfterAck?.status
        )
    }

    // =========================================================================
    // Test Doubles & Helpers
    // =========================================================================

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    class AuditTestChatDao : ChatDao {
        val messages = ConcurrentHashMap<String, ChatMessageEntity>()
        private var idSeq = 1L

        override suspend fun insert(message: ChatMessageEntity): Long {
            val id = if (message.id == 0L) idSeq++ else message.id
            messages[message.messageId] = message.copy(id = id)
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
            messages[messageId]?.let { messages[messageId] = it.copy(status = status) }
        }

        override suspend fun updateAckCount(messageId: String, ackPeerCount: Int, status: String) {
            messages[messageId]?.let { messages[messageId] = it.copy(ackPeerCount = ackPeerCount, status = status) }
        }

        override suspend fun recordPeerAck(messageId: String) {
            messages[messageId]?.let { messages[messageId] = it.copy(ackPeerCount = it.ackPeerCount + 1, status = "SENT_TO_PEERS") }
        }

        override suspend fun exists(messageId: String): Int = if (messages.containsKey(messageId)) 1 else 0

        override suspend fun count(missionId: String): Int = messages.values.count { it.missionId == missionId }

        override suspend fun getLatestSequence(senderId: String): Long? =
            messages.values.filter { it.senderId == senderId }.maxOfOrNull { it.authorSequence }

        override suspend fun getMessagesAfterSequence(senderId: String, afterSequence: Long): List<ChatMessageEntity> =
            messages.values.filter { it.senderId == senderId && it.authorSequence > afterSequence }.sortedBy { it.authorSequence }

        override suspend fun pruneOldMessages(missionId: String, keepCount: Int): Int = 0
    }

    class AuditTestMeshTransport : MeshTransport {
        private val _connectedPeers = MutableStateFlow<Map<String, PeerInfo>>(emptyMap())
        override val connectedPeers: StateFlow<Map<String, PeerInfo>> = _connectedPeers.asStateFlow()

        private val _incomingMessages = MutableSharedFlow<Pair<String, SyncMessage>>(extraBufferCapacity = 64)
        override val incomingMessages: SharedFlow<Pair<String, SyncMessage>> = _incomingMessages.asSharedFlow()

        private val _incomingFiles = MutableStateFlow<IncomingFileTransfer?>(null)
        override val incomingFiles: StateFlow<IncomingFileTransfer?> = _incomingFiles.asStateFlow()

        private val _meshState = MutableStateFlow(MeshState.ACTIVE)
        override val meshState: StateFlow<MeshState> = _meshState.asStateFlow()

        val sentMessages = CopyOnWriteArrayList<Pair<String, SyncMessage>>()
        val sentFiles = CopyOnWriteArrayList<Pair<String, File>>()
        private val payloadIdGen = AtomicLong(1000L)

        fun addPeer(deviceId: String, endpointId: String, name: String = deviceId) {
            _connectedPeers.value = _connectedPeers.value + (deviceId to PeerInfo(deviceId, endpointId, name))
        }

        fun removePeer(deviceId: String) {
            _connectedPeers.value = _connectedPeers.value - deviceId
        }

        override fun startAdvertisingAndDiscovery(deviceName: String, role: String) {
            _meshState.value = MeshState.ACTIVE
        }

        override fun stop() {
            _meshState.value = MeshState.STOPPED
        }

        override fun setOnPeerConnected(callback: (String, PeerInfo) -> Unit) {}
        override fun setOnPeerDisconnected(callback: (String) -> Unit) {}

        override suspend fun sendMessage(endpointId: String, message: SyncMessage): Boolean {
            sentMessages.add(endpointId to message)
            return true
        }

        override suspend fun broadcastMessage(message: SyncMessage, excludeEndpoint: String?): Int {
            sentMessages.add("BROADCAST" to message)
            return _connectedPeers.value.size
        }

        override fun sendFilePayload(endpointId: String, file: File): Long? {
            sentFiles.add(endpointId to file)
            return payloadIdGen.incrementAndGet()
        }

        override fun getEndpointForDevice(deviceId: String): String? =
            _connectedPeers.value[deviceId]?.endpointId ?: "endpoint-$deviceId"

        override fun getBytesSent(): Long = 0L
        override fun getBytesSent(message: SyncMessage): Int = 128
    }
}
