package com.rescuemesh.ai.e2e

import com.rescuemesh.ai.data.entity.MapEdgeEntity
import com.rescuemesh.ai.data.entity.MapNodeEntity
import com.rescuemesh.ai.intelligence.RoutePlanner
import com.rescuemesh.ai.model.SyncMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method

/**
 * End-to-End Headless Multi-Node Virtual Mesh Engine Tests.
 *
 * Verifies N >= 5 concurrent nodes in-memory, network churn/latency/packet loss,
 * dynamic link disconnect/reconnect, partition lifecycles, and conflict preservation.
 *
 * Adheres strictly to:
 * - Invariant 1: Strictly offline-first (zero external network/cloud calls).
 * - Invariant 2: Durable local-first persistence (committed to Room DAO fakes before transmission).
 * - Invariant 3: Conflict-preserving map fusion (contradictory reports preserved as CONFLICTED with full audit history).
 * - Invariant 4: Routes NEVER described as "safe" in code, test names, assertions, or comments.
 * - Invariant 5: Value-of-Information safety floor (P >= 0.80 for HAZARD and BLOCKED routes).
 */
class VirtualMeshEngineTest {

    private lateinit var mesh: VirtualMeshHarness
    private val missionId = "e2e-disaster-mission"

    @Before
    fun setUp() {
        mesh = VirtualMeshHarness(defaultPacketLossRate = 0.0, defaultMissionId = missionId)
    }

    private fun setupFiveNodeDisasterTeam(): List<SimulatedNode> {
        val nodeCmd = mesh.registerNode("node-cmd", "Incident Commander")
        val nodeRecon1 = mesh.registerNode("node-recon-1", "Recon Alpha")
        val nodeRecon2 = mesh.registerNode("node-recon-2", "Recon Bravo")
        val nodeMedic = mesh.registerNode("node-medic", "Medical Lead")
        val nodeEvac = mesh.registerNode("node-evac", "Evacuation Guide")
        return listOf(nodeCmd, nodeRecon1, nodeRecon2, nodeMedic, nodeEvac)
    }

    private suspend fun seedMapTopology(nodes: List<SimulatedNode>) {
        val mapNodes = listOf(
            MapNodeEntity(nodeId = "loc-base", missionId = missionId, label = "Staging Base", floor = 1, latitude = 40.71, longitude = -74.00),
            MapNodeEntity(nodeId = "loc-lobby", missionId = missionId, label = "Ground Lobby", floor = 1, latitude = 40.711, longitude = -74.001),
            MapNodeEntity(nodeId = "loc-corridor-1", missionId = missionId, label = "West Corridor", floor = 1, latitude = 40.712, longitude = -74.002),
            MapNodeEntity(nodeId = "loc-triage", missionId = missionId, label = "Triage Point", floor = 1, latitude = 40.713, longitude = -74.003),
            MapNodeEntity(nodeId = "loc-exit", missionId = missionId, label = "North Exit", floor = 1, latitude = 40.714, longitude = -74.004)
        )

        val mapEdges = listOf(
            MapEdgeEntity(edgeId = "edge-base-lobby", missionId = missionId, fromNodeId = "loc-base", toNodeId = "loc-lobby", distance = 15.0f, routeStatus = "REPORTED_OPEN"),
            MapEdgeEntity(edgeId = "edge-lobby-corridor", missionId = missionId, fromNodeId = "loc-lobby", toNodeId = "loc-corridor-1", distance = 25.0f, routeStatus = "UNKNOWN"),
            MapEdgeEntity(edgeId = "edge-corridor-triage", missionId = missionId, fromNodeId = "loc-corridor-1", toNodeId = "loc-triage", distance = 30.0f, routeStatus = "REPORTED_OPEN"),
            MapEdgeEntity(edgeId = "edge-triage-exit", missionId = missionId, fromNodeId = "loc-triage", toNodeId = "loc-exit", distance = 20.0f, routeStatus = "REPORTED_OPEN"),
            MapEdgeEntity(edgeId = "edge-lobby-exit-bypass", missionId = missionId, fromNodeId = "loc-lobby", toNodeId = "loc-exit", distance = 160.0f, routeStatus = "REPORTED_OPEN")
        )

        for (node in nodes) {
            node.mapDao.insertNodes(mapNodes)
            node.mapDao.insertEdges(mapEdges)
        }
    }

    @Test
    fun testMultiNodeRegistrationAndDigestExchangeFiveNodes() = runBlocking {
        val team = setupFiveNodeDisasterTeam()
        assertEquals(5, team.size)
        assertEquals(5, mesh.nodes.size)

        // Initial digest on all nodes should have empty sequences
        for (node in team) {
            val digest = node.generateStateDigest()
            assertEquals(node.nodeId, digest.deviceId)
            assertTrue(digest.sequences.isEmpty())
        }

        // Each node creates 1 local observation
        for (node in team) {
            node.createObservation(
                eventType = "NOTE",
                title = "Status Check from ${node.nodeId}",
                description = "Node operational"
            )
        }

        // Run one sync round across the mesh
        mesh.executeSyncRound(schedulerType = "VOI", byteBudgetPerWindow = 5000)

        // All 5 nodes should now have 5 observations (1 local + 4 received)
        for (node in team) {
            val allObs = node.observationDao.getAll(missionId)
            assertEquals("Node ${node.nodeId} should have received all 5 observations", 5, allObs.size)
        }
    }

    @Test
    fun testDynamicLinkDisruptionAndReconnection() = runBlocking {
        val team = setupFiveNodeDisasterTeam()
        val nodeCmd = team[0]
        val nodeRecon1 = team[1]

        // Disconnect direct link between Commander and Recon Alpha
        mesh.disconnectLink(nodeCmd.nodeId, nodeRecon1.nodeId)
        assertFalse(mesh.canCommunicate(nodeCmd.nodeId, nodeRecon1.nodeId))
        assertFalse(mesh.canCommunicate(nodeRecon1.nodeId, nodeCmd.nodeId))

        // Commander logs a hazard
        val hazard = nodeCmd.createObservation(
            eventType = "HAZARD",
            title = "Gas Leak in Sector 4",
            description = "High methane levels detected"
        )

        // Direct delivery attempt over broken link must fail
        val directDelivered = mesh.deliverMessage(
            fromNodeId = nodeCmd.nodeId,
            toNodeId = nodeRecon1.nodeId,
            message = SyncMessage.EventBatch(
                senderDeviceId = nodeCmd.nodeId,
                events = listOf()
            )
        )
        assertFalse(directDelivered)
        assertFalse(nodeRecon1.observationDao.exists(hazard.eventId))

        // Reconnect link
        mesh.reconnectLink(nodeCmd.nodeId, nodeRecon1.nodeId)
        assertTrue(mesh.canCommunicate(nodeCmd.nodeId, nodeRecon1.nodeId))

        // Run sync round
        mesh.executeSyncRound(schedulerType = "VOI", byteBudgetPerWindow = 2000)

        // Recon 1 should now have the hazard
        assertTrue(nodeRecon1.observationDao.exists(hazard.eventId))
    }

    @Test
    fun testProbabilisticPacketLossWithRetransmission() = runBlocking {
        val team = setupFiveNodeDisasterTeam()
        val nodeCmd = team[0]

        // Introduce 30% packet loss rate
        mesh.packetLossRate = 0.30

        val obs = nodeCmd.createObservation(
            eventType = "NOTE",
            title = "Weather Alert",
            description = "Heavy rain impacting ingress route"
        )

        // Under 30% loss, running multiple rounds guarantees eventual full dissemination
        val roundsNeeded = mesh.getHazardPropagationRounds(
            hazardEventId = obs.eventId,
            schedulerType = "VOI",
            byteBudgetPerWindow = 2000,
            maxRounds = 10
        )

        assertTrue("Rounds needed should be <= 10 even under 30% loss", roundsNeeded <= 10)
        for (node in team) {
            assertTrue(node.observationDao.exists(obs.eventId))
        }
    }

    @Test
    fun testNetworkPartitionLifecycleDisjointSets() = runBlocking {
        val team = setupFiveNodeDisasterTeam()
        val groupA = setOf("node-cmd", "node-recon-1")
        val groupB = setOf("node-recon-2", "node-medic", "node-evac")

        // Split mesh into two disconnected partitions
        mesh.partitionMesh(groupA, groupB)

        // Verify isolation
        assertFalse(mesh.canCommunicate("node-cmd", "node-medic"))
        assertFalse(mesh.canCommunicate("node-recon-1", "node-recon-2"))
        assertTrue(mesh.canCommunicate("node-cmd", "node-recon-1"))
        assertTrue(mesh.canCommunicate("node-medic", "node-evac"))

        // Group A creates observation
        val obsAlpha = team[0].createObservation(
            eventType = "NOTE",
            title = "Group A Ingress Report",
            description = "Sector A entrance secured"
        )

        // Group B creates observation
        val obsBravo = team[2].createObservation(
            eventType = "NOTE",
            title = "Group B Supply Cache",
            description = "Medical kit staged at waypoint"
        )

        // Run sync round within partitions
        mesh.executeSyncRound(schedulerType = "VOI", byteBudgetPerWindow = 5000)

        // Group A nodes have obsAlpha, but NOT obsBravo
        assertTrue(team[0].observationDao.exists(obsAlpha.eventId))
        assertTrue(team[1].observationDao.exists(obsAlpha.eventId))
        assertFalse(team[0].observationDao.exists(obsBravo.eventId))
        assertFalse(team[1].observationDao.exists(obsBravo.eventId))

        // Group B nodes have obsBravo, but NOT obsAlpha
        assertTrue(team[2].observationDao.exists(obsBravo.eventId))
        assertTrue(team[3].observationDao.exists(obsBravo.eventId))
        assertTrue(team[4].observationDao.exists(obsBravo.eventId))
        assertFalse(team[2].observationDao.exists(obsAlpha.eventId))
        assertFalse(team[3].observationDao.exists(obsAlpha.eventId))

        // Heal the partition
        mesh.healMesh()
        assertTrue(mesh.canCommunicate("node-cmd", "node-medic"))

        // Sync round across whole mesh
        mesh.executeSyncRound(schedulerType = "VOI", byteBudgetPerWindow = 5000)

        // All 5 nodes must now possess BOTH observations
        for (node in team) {
            assertTrue("Node ${node.nodeId} must have obsAlpha", node.observationDao.exists(obsAlpha.eventId))
            assertTrue("Node ${node.nodeId} must have obsBravo", node.observationDao.exists(obsBravo.eventId))
        }
    }

    /**
     * Invariant 3 (Conflict-Preserving Map Fusion):
     * Contradicting observations on graph edges must NEVER overwrite earlier evidence.
     * Disagreeing reports mark an edge as CONFLICTED and append to the conflict history.
     */
    @Test
    fun testPartitionConflictPreservationContradictoryReportsOnSameEdge() = runBlocking {
        val team = setupFiveNodeDisasterTeam()
        seedMapTopology(team)

        val groupA = setOf("node-cmd", "node-recon-1")
        val groupB = setOf("node-recon-2", "node-medic", "node-evac")
        mesh.partitionMesh(groupA, groupB)

        // Group A: Recon 1 observes corridor as OPEN
        val obsOpen = team[1].createObservation(
            eventType = "ROUTE_STATUS",
            title = "Corridor clear",
            reportedStatus = "OPEN",
            mapEdgeId = "edge-lobby-corridor"
        )
        // Group A local map updates to REPORTED_OPEN
        assertEquals("REPORTED_OPEN", team[1].mapDao.getEdge("edge-lobby-corridor")?.routeStatus)

        // Group B: Recon 2 observes corridor as BLOCKED
        val obsBlocked = team[2].createObservation(
            eventType = "ROUTE_STATUS",
            title = "Corridor collapsed",
            reportedStatus = "BLOCKED",
            mapEdgeId = "edge-lobby-corridor"
        )
        // Group B local map updates to REPORTED_BLOCKED
        assertEquals("REPORTED_BLOCKED", team[2].mapDao.getEdge("edge-lobby-corridor")?.routeStatus)

        // Reconnect and heal partition
        mesh.healMesh()

        // Sync round merges state digests and exchanges contradictory observations
        mesh.executeSyncRound(schedulerType = "VOI", byteBudgetPerWindow = 5000)

        // Invariant 3: Edge MUST transition to CONFLICTED on ALL 5 nodes, preserving both observation IDs
        for (node in team) {
            val edge = node.mapDao.getEdge("edge-lobby-corridor")
            assertNotNull("Edge must exist on ${node.nodeId}", edge)
            assertEquals(
                "Edge on ${node.nodeId} must be CONFLICTED after merging contradictory reports",
                "CONFLICTED",
                edge!!.routeStatus
            )

            // Audit history check
            val conflicts = edge.conflictingObservationIds ?: ""
            assertTrue(
                "Conflict audit trail on ${node.nodeId} must include open report ${obsOpen.eventId}",
                conflicts.contains(obsOpen.eventId)
            )
            assertTrue(
                "Conflict audit trail on ${node.nodeId} must include blocked report ${obsBlocked.eventId}",
                conflicts.contains(obsBlocked.eventId)
            )
        }
    }

    @Test
    fun testHumanVerificationSurveyResolvesConflictedEdgePostMerge() = runBlocking {
        val team = setupFiveNodeDisasterTeam()
        seedMapTopology(team)

        val groupA = setOf("node-cmd", "node-recon-1")
        val groupB = setOf("node-recon-2", "node-medic", "node-evac")
        mesh.partitionMesh(groupA, groupB)

        val obsOpen = team[1].createObservation(
            eventType = "ROUTE_STATUS",
            title = "Open corridor report",
            reportedStatus = "OPEN",
            mapEdgeId = "edge-lobby-corridor"
        )
        val obsBlocked = team[2].createObservation(
            eventType = "ROUTE_STATUS",
            title = "Blocked corridor report",
            reportedStatus = "BLOCKED",
            mapEdgeId = "edge-lobby-corridor"
        )

        mesh.healMesh()
        mesh.executeSyncRound(schedulerType = "VOI", byteBudgetPerWindow = 5000)

        // Verify initially CONFLICTED
        assertEquals("CONFLICTED", team[0].mapDao.getEdge("edge-lobby-corridor")?.routeStatus)

        // Authorized surveyor (Incident Commander) conducts on-site survey and confirms BLOCKED with VERIFIED status
        val verifiedObs = team[0].createObservation(
            eventType = "ROUTE_STATUS",
            title = "Surveyor confirmed blockage",
            reportedStatus = "BLOCKED",
            verificationStatus = "VERIFIED",
            mapEdgeId = "edge-lobby-corridor"
        )

        // Propagate across mesh
        mesh.executeSyncRound(schedulerType = "VOI", byteBudgetPerWindow = 5000)

        // All nodes must now resolve to VERIFIED_BLOCKED
        for (node in team) {
            val edge = node.mapDao.getEdge("edge-lobby-corridor")
            assertNotNull(edge)
            assertEquals(
                "Edge on ${node.nodeId} must be resolved to VERIFIED_BLOCKED",
                "VERIFIED_BLOCKED",
                edge!!.routeStatus
            )
            // Historical conflict IDs must remain preserved for forensic audit
            val conflicts = edge.conflictingObservationIds ?: ""
            assertTrue(conflicts.contains(obsOpen.eventId))
            assertTrue(conflicts.contains(obsBlocked.eventId))
        }
    }

    /**
     * Invariant 4 (Safety-Guaranteed Route Guidance):
     * Routes must NEVER be described as "safe" in code, logs, or UI.
     * Conflicted passages incur heavy risk penalties, and routes include explicit warnings.
     */
    @Test
    fun testRoutePlannerDynamicCostUpdateAndWarningOnConflict() = runBlocking {
        val team = setupFiveNodeDisasterTeam()
        seedMapTopology(team)

        val commander = team[0]

        // 1. Initially, corridor is UNKNOWN. Path finder chooses through corridor with unverified warning.
        val initialRoute = commander.routePlanner.findRoute(
            fromNodeId = "loc-base",
            toNodeId = "loc-exit",
            missionId = missionId
        )
        assertTrue(initialRoute.isComplete)
        assertEquals(listOf("loc-base", "loc-lobby", "loc-corridor-1", "loc-triage", "loc-exit"), initialRoute.path)

        // 2. Mark corridor as CONFLICTED
        commander.mapDao.updateEdgeStatus(
            edgeId = "edge-lobby-corridor",
            status = "CONFLICTED",
            observationId = "obs-conflict-1",
            conflictIds = "obs-a,obs-b",
            updatedAt = System.currentTimeMillis()
        )

        // 3. Re-evaluate route avoiding conflicted edges
        val conflictedRoute = commander.routePlanner.findRoute(
            fromNodeId = "loc-base",
            toNodeId = "loc-exit",
            missionId = missionId,
            avoidConflicted = true
        )

        assertTrue(conflictedRoute.isComplete)
        // Due to CONFLICT penalty (weight +50.0), the planner selects the longer bypass edge
        assertEquals(listOf("loc-base", "loc-lobby", "loc-exit"), conflictedRoute.path)

        // 4. Invariant 4 assertion: Zero occurrences of "safe" in warnings or descriptions
        for (warning in conflictedRoute.warnings) {
            assertFalse(
                "Invariant 4 violation: warning contains 'safe': $warning",
                warning.lowercase().contains("safe")
            )
        }
    }

    @Test
    fun testInvariant4LanguageComplianceReflectionCheck() = runBlocking {
        val team = setupFiveNodeDisasterTeam()
        seedMapTopology(team)

        val result = team[0].routePlanner.findRoute(
            fromNodeId = "loc-base",
            toNodeId = "loc-exit",
            missionId = missionId
        )

        // Check pathLabels
        for (label in result.pathLabels) {
            assertFalse(
                "Invariant 4 violation: label contains 'safe': $label",
                label.lowercase().contains("safe")
            )
        }

        // Check declared methods in RoutePlanner
        val methods: Array<Method> = RoutePlanner::class.java.declaredMethods
        for (method in methods) {
            val name = method.name.lowercase()
            assertFalse(
                "Invariant 4 violation in RoutePlanner method '${method.name}'",
                name.contains("safe")
            )
        }
    }

    @Test
    fun testMultiHopRelayAcrossLinearTopology() = runBlocking {
        // Linear chain: node-1 <-> node-2 <-> node-3 <-> node-4 <-> node-5
        val n1 = mesh.registerNode("n1")
        val n2 = mesh.registerNode("n2")
        val n3 = mesh.registerNode("n3")
        val n4 = mesh.registerNode("n4")
        val n5 = mesh.registerNode("n5")

        // Disconnect all non-adjacent links
        mesh.disconnectLink("n1", "n3")
        mesh.disconnectLink("n1", "n4")
        mesh.disconnectLink("n1", "n5")
        mesh.disconnectLink("n2", "n4")
        mesh.disconnectLink("n2", "n5")
        mesh.disconnectLink("n3", "n5")

        // n1 creates observation
        val obs = n1.createObservation(
            eventType = "HAZARD",
            title = "Linear chain test hazard",
            description = "Testing multi-hop propagation"
        )

        // Propagate round by round along chain:
        // Round 1: n1 -> n2
        mesh.executeSyncRound(schedulerType = "VOI", byteBudgetPerWindow = 2000)
        assertTrue("n2 should have obs after round 1", n2.observationDao.exists(obs.eventId))

        // Round 2: n2 -> n3
        mesh.executeSyncRound(schedulerType = "VOI", byteBudgetPerWindow = 2000)
        assertTrue("n3 should have obs after round 2", n3.observationDao.exists(obs.eventId))

        // Round 3: n3 -> n4
        mesh.executeSyncRound(schedulerType = "VOI", byteBudgetPerWindow = 2000)
        assertTrue("n4 should have obs after round 3", n4.observationDao.exists(obs.eventId))

        // Round 4: n4 -> n5
        mesh.executeSyncRound(schedulerType = "VOI", byteBudgetPerWindow = 2000)
        assertTrue("n5 should have obs after round 4", n5.observationDao.exists(obs.eventId))
    }

    @Test
    fun testDuplicatePacketSuppressionRedundantMeshLinks() = runBlocking {
        val team = setupFiveNodeDisasterTeam()

        val obs = team[0].createObservation(
            eventType = "NOTE",
            title = "Mesh Broadcast Test",
            description = "Observation broadcast across full mesh"
        )

        // Multiple redundant sync rounds executed
        for (i in 1..4) {
            mesh.executeSyncRound(schedulerType = "VOI", byteBudgetPerWindow = 5000)
        }

        // Each node must have received and stored the observation EXACTLY ONCE
        for (node in team) {
            val count = node.observationDao.getAll(missionId).count { it.eventId == obs.eventId }
            assertEquals("Observation should appear exactly once in database of ${node.nodeId}", 1, count)
        }
    }

    @Test
    fun testOutboxDurablePersistenceAndAckReceiptCycle() = runBlocking {
        val nodeA = mesh.registerNode("node-a")
        val nodeB = mesh.registerNode("node-b")

        // Invariant 2: Create observation committed to outbox as PENDING
        val obs = nodeA.createObservation(
            eventType = "NOTE",
            title = "Receipt verification test"
        )

        val pendingBefore = nodeA.outboxDao.getPendingByFifo()
        assertEquals(1, pendingBefore.size)
        assertEquals("PENDING", pendingBefore[0].status)

        // Deliver batch to Node B
        val batch = SyncMessage.EventBatch(
            senderDeviceId = "node-a",
            events = listOf(
                com.rescuemesh.ai.model.SyncObservation(
                    eventId = obs.eventId,
                    missionId = obs.missionId,
                    authorDeviceId = obs.authorDeviceId,
                    authorSequence = obs.authorSequence,
                    eventType = obs.eventType,
                    title = obs.title,
                    description = obs.description,
                    observedAtDevice = obs.observedAtDevice
                )
            )
        )

        mesh.deliverMessage("node-a", "node-b", batch)

        // Node B should send EventAck which Node A receives and updates outbox to DELIVERED
        val pendingAfter = nodeA.outboxDao.getPendingByFifo()
        assertTrue("Outbox should have zero PENDING entries after ACK receipt", pendingAfter.isEmpty())

        val receipts = nodeA.outboxDao.getReceipts(obs.eventId)
        assertEquals(1, receipts.size)
        assertEquals("node-b", receipts[0].peerDeviceId)
    }

    @Test
    fun testSingleNodeIsolatedPartitionBoundary() = runBlocking {
        val team = setupFiveNodeDisasterTeam()
        val isolatedNode = team[0]
        val restOfTeam = setOf("node-recon-1", "node-recon-2", "node-medic", "node-evac")

        // 1 vs 4 partition
        mesh.partitionMesh(setOf(isolatedNode.nodeId), restOfTeam)

        // Isolated node creates 5 observations
        for (i in 1..5) {
            isolatedNode.createObservation(
                eventType = "NOTE",
                title = "Isolated note $i"
            )
        }

        // Remaining team creates 3 observations
        for (i in 1..3) {
            team[1].createObservation(
                eventType = "NOTE",
                title = "Team note $i"
            )
        }

        mesh.executeSyncRound(schedulerType = "VOI", byteBudgetPerWindow = 5000)

        // Isolated node has only 5 observations
        assertEquals(5, isolatedNode.observationDao.getAll(missionId).size)

        // Reconnect
        mesh.healMesh()
        mesh.executeSyncRound(schedulerType = "VOI", byteBudgetPerWindow = 5000)

        // All nodes have 8 observations
        for (node in team) {
            assertEquals("Node ${node.nodeId} should have all 8 observations", 8, node.observationDao.getAll(missionId).size)
        }
    }

    @Test
    fun testPartitionThrashingStressTest() = runBlocking {
        val team = setupFiveNodeDisasterTeam()

        // Rapid partition and heal cycles while creating observations
        for (cycle in 1..5) {
            mesh.partitionMesh(
                setOf("node-cmd", "node-recon-1"),
                setOf("node-recon-2", "node-medic", "node-evac")
            )
            team[0].createObservation(eventType = "NOTE", title = "Thrash A cycle $cycle")
            team[2].createObservation(eventType = "NOTE", title = "Thrash B cycle $cycle")

            mesh.executeSyncRound(schedulerType = "VOI", byteBudgetPerWindow = 2000)

            mesh.healMesh()
            mesh.executeSyncRound(schedulerType = "VOI", byteBudgetPerWindow = 2000)
        }

        // Final sync rounds to ensure full convergence
        for (i in 1..3) {
            mesh.executeSyncRound(schedulerType = "VOI", byteBudgetPerWindow = 5000)
        }

        // 10 total observations created (2 per cycle * 5 cycles)
        for (node in team) {
            val total = node.observationDao.getAll(missionId).size
            assertEquals("Node ${node.nodeId} must converge to all 10 observations after thrashing", 10, total)
        }
    }
}
