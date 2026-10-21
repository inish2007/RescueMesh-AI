# RescueMesh AI — Physical Android Application Transition Test Infrastructure Specification

**Version**: 2.0.0  
**Status**: ACTIVE & VERIFIED  
**Track**: E2E Testing Track (`e2e_test_writer_3`)  
**Scope**: Transition of RescueMesh AI to an Installable, Offline Android Application on Physical Devices in Network-Denied Environments  
**Target Architecture**: Host JVM (OpenJDK 25 / Kotlin 2.0.21 / JUnit 4 / Kotlin Coroutines)

---

## 1. Executive Summary & Testing Philosophy

RescueMesh AI is transitioning from headless simulation to an installable, offline Android application executing on physical devices in network-denied disaster environments. Automated continuous integration cannot depend on physical Android radios, live GPS locks, or cloud services. Therefore, the E2E testing framework provides **Opaque-Box E2E Testing Infrastructure** that evaluates full application workflows—from hardware contract boundaries and Room persistence to mesh handshake authentication, outbox retry loops, and map fusion—with 100% deterministic fidelity on the host JVM.

### Core Testing Invariants (Zero Exceptions)

1. **Strictly Offline-First (Invariant 1)**:
   - The application and all tests operate with zero mandatory internet, cloud, or external network infrastructure.
   - Zero dependencies on Firebase, remote servers, or external telemetry.
   - Inter-device mesh communication uses Google Nearby Connections (`P2P_CLUSTER`) simulated via state-machine transport contracts.

2. **Durable Local-First Persistence (Invariant 2)**:
   - Every observation, status change, and outbox entry MUST be committed to local persistence before any network transmission attempt.
   - Zero memory-only states that do not survive process restarts.
   - Crash recovery restores orphaned `SENDING` outbox entries back to `PENDING` upon startup.

3. **Conflict-Preserving Map Fusion (Invariant 3)**:
   - Contradicting observations on graph edges across devices never overwrite earlier evidence.
   - Disagreeing reports mark an edge as `CONFLICTED` and preserve complete audit history (`conflictingObservationIds`).
   - Conflicted passages are resolved exclusively through explicit human-verified surveys (`VERIFIED_CLEAR` / `VERIFIED_BLOCKED`).

4. **Safety-Guaranteed Route Guidance (Invariant 4)**:
   - Routes must **NEVER** be described as "safe" in code, logs, test names, assertions, or UI output.
   - Routes are strictly reported in terms of relative cost, surveyed distance, and explicit warning flags.
   - Confirmed blocked edges are strictly excluded from path recommendations.

5. **Priority Scheduling & Value-of-Information Safety Floor (Invariant 5)**:
   - Urgency reports (`HAZARD`, `BLOCKED` passages) enforce an absolute priority floor ($P \ge 0.80$).
   - High-urgency alerts cannot be suppressed by aging or transmission cost penalties.

---

## 2. Test Infrastructure Architecture

The test infrastructure models the complete hardware-software boundary of a physical disaster response device:

```
+--------------------------------------------------------------------------------------------------+
|                                    Physical Device E2E Harness                                    |
+--------------------------------------------------------------------------------------------------+
|                                                                                                  |
|  [RescueLocationProvider]          [PhotoEvidenceManager]              [MissionManager]          |
|  - Real Location Acquisition       - App-Private Storage (`evidence/`) - Active Mission Selector |
|  - Fallback to null/UNKNOWN        - SHA-256 Cryptographic Digest      - Pairing Code Lookups    |
|  - Manually Surveyed Indoor Coords - Cancellation Temp File Cleanup    - Cross-Mission Isolation |
|                                                                                                  |
+--------------------------------------------------------------------------------------------------+
                                                │
                                                ▼
+--------------------------------------------------------------------------------------------------+
|                                    Core Application Container                                    |
|  - Room Local Database (Observations, MapNodes, MapEdges, Outbox, Missions, Metrics)             |
|  - ObservationRepository & MapRepository (0 sample nodes seeded on cold start)                   |
|  - PriorityScheduler (VoI with P >= 0.80 floor)                                                  |
|  - MapFusionEngine (Conflict preservation & audit trails)                                        |
|  - RoutePlanner (Dijkstra pathfinder, risk penalties, zero 'safe' terminology)                  |
+--------------------------------------------------------------------------------------------------+
                                                │
                                                ▼
+--------------------------------------------------------------------------------------------------+
|                                   Mesh & Synchronization Engine                                  |
|  - SyncCoordinator with Bounded Exponential Backoff Retry Loop (1s -> 2s -> 4s -> ... -> 32s)   |
|  - Crash Recovery: resetOrphanedSendingToPending() on coordinator cold start                     |
|  - NearbyMeshTransport Handshake: MissionId authentication & mismatch rejection                   |
|  - Duplicate Suppression: Re-transmits EventAck for duplicates to prevent retry exhaustion       |
|  - Transport Telemetry Instrumentation: Bytes sent/rcvd, ACK latency, queue depth, disconnects   |
+--------------------------------------------------------------------------------------------------+
```

### Component Test Fixtures & Contracts

1. **`RescueLocationProvider` Contract**:
   ```kotlin
   data class LocationResult(
       val latitude: Double,
       val longitude: Double,
       val accuracy: Float?,
       val provider: String,
       val timestamp: Long = System.currentTimeMillis()
   )

   interface RescueLocationProvider {
       suspend fun getLastKnownLocation(): LocationResult?
       suspend fun getCurrentLocation(): LocationResult?
   }
   ```
   *Behavioral Invariant*: When GPS is unavailable (e.g. subterranean or collapsed concrete structures), provider returns `null`. Creation of observations records `null` latitude and longitude, strictly eliminating fabricated coordinates (such as Bangalore `12.9716, 77.5946`).

2. **`EvidenceManager` Contract**:
   ```kotlin
   interface EvidenceManager {
       fun createEvidenceUri(): Pair<Uri, File>
       fun computeSha256(file: File): String
       fun cleanupFile(file: File)
   }
   ```
   *Behavioral Invariant*: Computes deterministic 64-character lowercase hexadecimal SHA-256 digest from evidence files. When camera capture is cancelled, temporary files are immediately deleted without creating orphaned placeholders or attaching invalid hashes.

3. **`MissionDao` & Mission Isolation Contract**:
   *Behavioral Invariant*: Pairing codes are normalized (case-insensitive, trimmed). Responders must join an explicit mission; peers connecting with mismatched `missionId` in `SyncMessage.Hello` are rejected and forbidden from receiving state digests or observations.

4. **Outbox Retry & Crash Recovery Contract**:
   *Behavioral Invariant*: Outbox retry implements bounded exponential backoff $T(k) = \min(base \cdot 2^{k-1}, maxBackoff)$. Entries exceeding maximum retries transition to `FAILED`. Orphaned `SENDING` entries revert to `PENDING` upon coordinator restart.

5. **Duplicate Suppression with Mandatory ACK Contract**:
   *Behavioral Invariant*: When a duplicate event is received, it is ignored by the database to prevent mutation, but an `EventAck` is mandatorily dispatched back to the sender to acknowledge receipt and clear the sender's outbox.

---

## 3. The 4-Tier Opaque-Box E2E Test Suite

The test suite is implemented in `app/src/test/java/com/rescuemesh/ai/e2e/PhysicalAppTransitionE2ETest.kt`:

### Tier 1: Feature Coverage
1. `testGenuineLocationAcquisitionFallbackToNullWhenGpsUnavailable`: Verifies genuine location provider returns null in network-denied / indoor environments and observation creation records null coordinates with zero fabricated positions.
2. `testProductionColdStartContainsZeroSeedSampleMapNodes`: Verifies a clean cold start has 0 nodes and 0 edges, preventing synthetic sample map contamination.
3. `testMissionLifecycleCreationActiveSwitchAndPairingCodeLookup`: Verifies mission creation, case-insensitive trimmed pairing code lookup, and atomic active mission switching.
4. `testPhotoEvidenceSha256CalculationAndCancellationCleanup`: Verifies standard SHA-256 hex digest computation and ensures cancellation deletes temporary files without saving placeholder evidence.
5. `testMeshHandshakeMissionMatchingAndMismatchRejection`: Verifies `SyncMessage.Hello` with matching missionId succeeds, while mismatched missionId rejects peer connection.
6. `testOutboxRetryWithBoundedExponentialBackoff`: Verifies delay scaling across retries (1s, 2s, 4s, 8s, 16s, 32s) capped at maximum backoff.
7. `testCrashRecoveryForOrphanedSendingOutboxEntries`: Verifies entries stuck in `SENDING` during unexpected termination are reset to `PENDING` on reboot.
8. `testDuplicateSuppressionTransmitsAckToPreventRetryExhaustion`: Verifies duplicate observation batches do not re-insert into DB but return `EventAck` to clear sender outbox.
9. `testTransportTelemetryInstrumentationMetricsTracking`: Verifies tracking of bytes transmitted/received, connection events, queue depth, and ACK latency.

### Tier 2: Boundary & Corner Cases
1. `testEmptyNodeMapsGracefulHandling`: Verifies path planning and map queries return empty routes / unreachable costs without throwing exceptions when nodes are empty.
2. `testExtremeCoordinatesHandling`: Verifies handling of extreme latitudes ($\pm 90.0$) and longitudes ($\pm 180.0$) without overflow or NaN.
3. `testMaxRetriesExceededTransitionsToFailed`: Verifies an outbox entry failing repeatedly beyond max retry limit transitions to `FAILED` status.
4. `testRapidReconnectionStateDigestReconciliation`: Verifies repeated disconnect/reconnect cycles accurately reconcile missed sequences without data loss or duplicate records.
5. `testCorruptPayloadsGracefullyRejected`: Verifies malformed JSON, truncated buffers, and invalid envelopes are rejected without crashing transport or sync coordinator.
6. `testLargeEvidenceFileSha256Hashing`: Verifies streaming SHA-256 calculation on multi-megabyte files without OutOfMemoryError.

### Tier 3: Cross-Feature Combinations
1. `testMeshStateSynchronizationMixedVerifiedAndConflictedEdges`: Verifies multi-node mesh synchronization across open, conflicted, and human-verified edges converges all nodes to identical graph state.
2. `testMissionSwitchingWithIsolatedOutboxes`: Verifies outbox entries and observations generated under Mission A never leak to Mission B.
3. `testConcurrentObservationCreationAndDigestExchange`: Verifies concurrent coroutine observation creation during active digest exchange maintains monotonic sequences and zero race conditions.

### Tier 4: Real-World Application Scenarios
1. `testEndToEndCollapsedBuildingSearchAndRescueScenario`: Full multi-responder search and rescue operation in a network-denied collapsed building:
   - Team Alpha, Team Bravo, and Command join mission via pairing code.
   - Clean initial survey with genuine indoor locations.
   - Hazard observation with SHA-256 photo evidence.
   - Mesh propagation with VoI safety floor ($P \ge 0.80$).
   - Conflict preservation on contradictory corridor observations.
   - Official human survey resolution to `VERIFIED_BLOCKED`.
   - Route guidance strictly excluding blocked passages and reporting explicit warning flags.
   - Invariant 4 reflection audit confirming ZERO occurrences of the word `"safe"`.

---

## 4. Authoritative Expected Output Derivation

1. **Cryptographic SHA-256 Test Vectors**:
   - Standard NIST test vector:
     `"" (empty string)` $\to$ `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
   - Verified ASCII string:
     `"RescueMesh AI Disaster Evidence"` $\to$ computed via standard JVM `MessageDigest.getInstance("SHA-256")`.

2. **Bounded Exponential Backoff Formula**:
   $$T_{\text{backoff}}(k) = \min(T_{\text{base}} \cdot 2^{k-1}, T_{\text{max}})$$
   For $T_{\text{base}} = 1000\text{ms}$, $T_{\text{max}} = 32000\text{ms}$:
   - $k=1 \implies 1000\text{ms}$
   - $k=2 \implies 2000\text{ms}$
   - $k=3 \implies 4000\text{ms}$
   - $k=4 \implies 8000\text{ms}$
   - $k=5 \implies 16000\text{ms}$
   - $k=6 \implies 32000\text{ms}$
   - $k \ge 7 \implies 32000\text{ms}$

3. **VoI Safety Floor Override Invariant**:
   For any observation where $U \ge 0.90$ (Hazard $U=1.00$, Blocked $U=0.95$):
   $$S_{\text{final}} = \max(S_{\text{raw}}, 0.80) \ge 0.80$$
   Safety-critical alerts always sort ahead of routine notes ($S_{\text{note}} \le 0.40$).

4. **Safety-Guaranteed Route Guidance Invariant**:
   Any route query touching a `VERIFIED_BLOCKED` or `REPORTED_BLOCKED` edge MUST exclude that edge from viable traversal paths, and must NEVER describe any route or path as `"safe"`.

---

## 5. Execution & Verification Guide

```bash
# 1. Compile debug Kotlin classes
cmd /c "gradlew.bat compileDebugKotlin"

# 2. Run specifically the Physical App Transition E2E test suite
cmd /c "gradlew.bat testDebugUnitTest --tests com.rescuemesh.ai.e2e.PhysicalAppTransitionE2ETest --no-daemon"

# 3. Run all unit and E2E test suites (regression check)
cmd /c "gradlew.bat testDebugUnitTest --no-daemon"
```
