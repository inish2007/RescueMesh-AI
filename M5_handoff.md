# Milestone 5 Architecture & Handoff Specification — Real-Device Mesh Synchronization & Outbox Resilience
**Target**: Milestone 5 Autonomous Multi-Agent Execution | **Status**: **READY FOR INGESTION**  
**Author**: Agent C (Independent QA & Security Engineer) | **Date**: 2026-10-21  

---

## 1. Executive Summary & Objective

With Milestone 4 verified and certified (189/189 tests passing, clean compile/lint, and installable debug APK generated), RescueMesh AI transitions in Milestone 5 from local-first data integrity to **real-device offline peer-to-peer mesh synchronization**.

Milestone 5 focuses on:
1. **Mission-Authenticated Handshake**: Cryptographic/protocol validation of peer mission IDs in `SyncMessage.Hello`, with automatic disconnection on mismatch.
2. **Process Death & Crash Recovery**: Reversion of orphaned `SENDING` outbox entries to `PENDING` on coordinator start.
3. **Bounded Exponential Backoff Retry Loop**: Reliable outbox queue processing with safety priority overrides ($P \ge 0.80$).
4. **Duplicate Suppression & Sender ACK Resolution**: Returning `EventAck` for duplicate transmissions to prevent sender retry starvation.
5. **Post-Reconnect State Digest Reconciliation**: Automatic recovery of missed observations across radio dropouts.
6. **Real-Time Transport Telemetry**: Byte counters, ACK latency, queue depth, and retry statistics tracked in `MetricsDao` and visualized on `MetricsScreen`.

---

## 2. Detailed Technical Specifications

### 2.1 Genuine Mission Handshake & Peer Isolation (Features 19 & 20)
- **Problem**: In earlier mock iterations, `NearbyMeshTransport.kt` transmitted placeholder or hardcoded mission IDs in `SyncMessage.Hello`.
- **Specification**:
  - `NearbyMeshTransport` must receive the dynamic `missionIdProvider: () -> String` from `RescueMeshApp`.
  - When connection is established, the initiator sends `SyncMessage.Hello`:
    ```kotlin
    data class Hello(
        val deviceId: String,
        val missionId: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : SyncMessage()
    ```
  - **Authentication Guard**: In `handleIncomingMessage(Hello)`:
    ```kotlin
    if (hello.missionId != activeMissionId) {
        Log.w(TAG, "Rejected peer ${hello.deviceId}: foreign mission ${hello.missionId} != active $activeMissionId")
        nearbyConnections.disconnectFromEndpoint(endpointId)
        return
    }
    ```
  - Observations received from unauthenticated endpoints must be dropped immediately.

### 2.2 Outbox Crash Recovery (Feature 21)
- **Problem**: If Android OS terminates the application process (e.g., low memory killer) while an outbox payload is in `status = 'SENDING'`, that entry remains orphaned in `SENDING` state on reboot and is never transmitted.
- **Specification**:
  - In `app/src/main/java/com/rescuemesh/ai/data/dao/OutboxDao.kt` (and `FakeDaos.kt`):
    ```kotlin
    @Query("UPDATE outbox SET status = 'PENDING' WHERE status = 'SENDING'")
    suspend fun resetOrphanedSendingToPending(): Int
    ```
  - In `SyncCoordinator.start()`:
    ```kotlin
    val recoveredCount = outboxDao.resetOrphanedSendingToPending()
    if (recoveredCount > 0) {
        Log.i(TAG, "Crash recovery: reset $recoveredCount orphaned SENDING outbox entries to PENDING")
    }
    ```

### 2.3 Persistent Outbox Retries with Exponential Backoff (Feature 22)
- **Problem**: Temporary BLE/Wi-Fi fading causes immediate transmission failure without a structured retry policy.
- **Specification**:
  - Add `retryCount: Int = 0` and `nextAttemptAt: Long = 0L` to `OutboxEntity`.
  - Bounded exponential backoff formula:
    $$\text{backoffMs} = \min\left(1000 \times 2^{\text{retryCount}}, 32000\right)$$
  - Retry logic in `SyncCoordinator.kt`:
    * Filter pending outbox entries where `System.currentTimeMillis() >= nextAttemptAt`.
    * If send succeeds: mark `DELIVERED`, record `DeliveryReceiptEntity`.
    * If send fails and `retryCount < MAX_RETRIES` (e.g. 5):
      increment `retryCount`, set `nextAttemptAt = now + backoffMs`, status remains `PENDING`.
    * If `retryCount >= MAX_RETRIES`: set status `FAILED`.
  - **Safety Override Invariant**: Observations with $P \ge 0.80$ (Hazards, Blocked passages) ignore backoff ceilings and are prioritized at the front of every send cycle.

### 2.4 Duplicate Suppression & ACK Resolution (Feature 23)
- **Problem**: If device A sends an `EventBatch` to device B, and device B receives it but A does not receive the ACK, A retries. If B silently ignores the duplicate without ACKing, A retries indefinitely until queue exhaustion.
- **Specification**:
  - In `SyncCoordinator.handleEventBatch(batch)`:
    * For each observation in batch:
      - If `observationDao.exists(obs.eventId)` is true: skip insertion, but record that event as known.
      - If `observationDao.exists(obs.eventId)` is false: insert into `observationDao` with `isLocal = false`.
    * Always construct and send `SyncMessage.EventAck(eventIds = batch.observations.map { it.eventId })` back to the sender endpoint.

### 2.5 State Digest Reconciliation on Peer Reconnect (Feature 24)
- **Specification**:
  - Upon connection established and authenticated:
    * Query local `observationDao.getAllSequenceDigests()` returning list of `SequenceDigest(authorDeviceId, maxSeq)`.
    * Transmit `SyncMessage.StateDigest(digests = localDigests)`.
  - Upon receiving `SyncMessage.StateDigest`:
    * Compare peer's `maxSeq` against local `maxSeq` per author device.
    * If local has higher sequences: enqueue missing observations in `outboxDao` for target peer.
    * If peer has higher sequences: send `SyncMessage.EventRequest(authorDeviceId, fromSequence = localMaxSeq + 1)`.

### 2.6 Real-Time Transport Telemetry Instrumentation (Features 25 & 26)
- **Specification**:
  - Record metric events in `MetricsDao`:
    * `BYTES_SENT`, `BYTES_RECEIVED`
    * `PEER_CONNECTED`, `PEER_DISCONNECTED`
    * `HANDSHAKE_LATENCY_MS`
    * `ACK_ROUND_TRIP_MS`
    * `OUTBOX_QUEUE_DEPTH`
  - Feed into `MetricsScreen.kt` for live tactical dashboard display.

---

## 3. Recommended Multi-Agent Ownership for Milestone 5

| Agent Role | Assigned Scope | Key Files |
|---|---|---|
| **Agent A (Mesh Networking)** | P2P Handshake Authentication, Mission Isolation, State Digest Protocol | `NearbyMeshTransport.kt`, `SyncMessage.kt` |
| **Agent B (Persistence & Outbox)** | Crash Recovery, Exponential Backoff Engine, Duplicate ACK Fix | `SyncCoordinator.kt`, `OutboxDao.kt`, `OutboxEntity.kt`, `PriorityScheduler.kt` |
| **Agent C (QA & Telemetry)** | Metrics Instrumentation, Multi-Node Simulation Tests, Adversarial Net Testing | `MetricsDao.kt`, `MetricsScreen.kt`, `VirtualMeshSimulationTest.kt`, `SyncCoordinatorEmpiricalTest.kt` |

---

## 4. Milestone 5 Verification Gate Checklist

Before declaring Milestone 5 complete, the engineering team must satisfy:
- [ ] `cmd /c "gradlew.bat compileDebugKotlin"` completes with 0 errors.
- [ ] `cmd /c "gradlew.bat testDebugUnitTest --rerun-tasks"` passes 100% of tests (target: $\ge 200$ tests).
- [ ] Headless multi-node test proves that partitioned nodes reconcile missing state upon reconnection.
- [ ] Orphaned `SENDING` outbox entries are proven to revert to `PENDING` on coordinator reboot.
- [ ] `cmd /c "gradlew.bat lintDebug"` passes with 0 fatal errors.
- [ ] `cmd /c "gradlew.bat assembleDebug"` builds valid `app-debug.apk`.
- [ ] Zero routes described as "safe".
