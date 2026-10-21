package com.rescuemesh.ai.e2e

import com.rescuemesh.ai.simulation.SimulatedRescueNode
import com.rescuemesh.ai.simulation.VirtualMeshEnvironment

/**
 * Headless In-Memory Virtual Mesh Simulation Harness.
 *
 * Coordinates N >= 5 concurrent simulated rescue nodes exchanging sync protocol messages
 * (Hello, StateDigest, EventBatch, EventAck, EventRequest) over simulated links.
 * Models network latency, packet loss, dynamic link disconnect/reconnect, and partition lifecycles.
 * Modular delegation to [VirtualMeshEnvironment] and [SimulatedRescueNode].
 *
 * Adheres strictly to:
 * - Invariant 1: Strictly offline-first (zero external network/cloud calls).
 * - Invariant 2: Durable local-first persistence (committed to Room DAO fakes before transmission).
 * - Invariant 3: Conflict-preserving map fusion (contradictory reports preserved as CONFLICTED with full audit history).
 * - Invariant 4: Routes NEVER described as "safe" in code, test names, assertions, or comments.
 * - Invariant 5: Value-of-Information safety floor (P >= 0.80 for HAZARD and BLOCKED routes).
 */
typealias SimulatedNode = SimulatedRescueNode

class VirtualMeshHarness(
    val defaultPacketLossRate: Double = 0.0,
    defaultMissionId: String = "disaster-mesh-alpha"
) : VirtualMeshEnvironment(
    packetLossRate = defaultPacketLossRate,
    defaultMissionId = defaultMissionId
)
