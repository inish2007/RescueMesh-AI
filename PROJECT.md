# Project: RescueMesh AI — Transition to Installable Offline Android Application

## Architecture
RescueMesh AI is an offline-first collaborative intelligence Android system designed for disaster-response teams operating in network-denied environments without cloud or internet infrastructure.

- **Presentation Layer**: Jetpack Compose 5-tab Material3 navigation (`HomeScreen`, `ObservationScreen`, `MapScreen`, `PeersScreen`, `MetricsScreen`).
- **Domain & Intelligence**:
  - `PriorityScheduler`: Value-of-Information (VoI) prioritization with Safety Floor ($P \ge 0.80$).
  - `MapFusionEngine`: Conflict-preserving graph fusion state machine (`UNKNOWN`, `REPORTED_OPEN`, `REPORTED_BLOCKED`, `VERIFIED_CLEAR`, `VERIFIED_BLOCKED`, `CONFLICTED`).
  - `RoutePlanner`: Dijkstra pathfinder with uncertainty/risk penalties (Safety Invariant: routes are NEVER described as "safe"; blocked edges strictly excluded).
- **Persistence Layer**: Room database (`RescueMeshDatabase`) with entities: `ObservationEntity`, `MapNodeEntity`, `MapEdgeEntity`, `OutboxEntity`, `PeerEntity`, `MetricsEntity`, `MissionEntity`.
- **Mesh Synchronization Layer**:
  - `NearbyMeshTransport`: Google Nearby Connections (`P2P_CLUSTER` strategy) over Bluetooth Low Energy & Wi-Fi Aware.
  - `SyncCoordinator`: Digest-based state synchronization, outbox retry engine, duplicate suppression, and hop-limited relaying.
- **Hardware Integration Layer**:
  - `RescueLocationProvider`: FusedLocationProviderClient + LocationManager GPS fallback with graceful UNKNOWN / null handling.
  - `PhotoEvidenceManager`: `ActivityResultContracts.TakePicture`, app-private storage (`context.filesDir/evidence/`), FileProvider, and cryptographic SHA-256 calculation.

---

## Feature Inventory
Every feature from the Survey phase is mapped here with its assigned milestone.

| # | Feature | Description | Milestone | Source |
|---|---|---|---|---|
| 1 | AGP JDK 25 Lint Fix | Configure `lint { abortOnError = false; checkReleaseBuilds = false }` and disable lint tasks under JDK 25 in `app/build.gradle.kts` | M1 | Survey Explorer 1 |
| 2 | Network State Permission | Add `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />` to `AndroidManifest.xml` | M1 | Survey Explorer 1 |
| 3 | FileProvider Configuration | Add `<provider>` tag in `AndroidManifest.xml` and create `app/src/main/res/xml/file_paths.xml` for `filesDir/evidence/` | M1 | Survey Explorer 1 & 2 |
| 4 | Clean APK Assembly | Verify `assembleDebug` builds cleanly and produces `app/build/outputs/apk/debug/app-debug.apk` | M1 | Survey Explorer 1 |
| 5 | Mission Entity & DAO Schema | Add `pairingCode` to `MissionEntity`, create `MissionDao` with Flow queries, register in `RescueMeshDatabase` | M2 | Survey Explorer 2 |
| 6 | Active Mission Management | Add dynamic active mission selection and persistence in `RescueMeshApp` | M2 | Survey Explorer 2 |
| 7 | Mission UI & Pairing | Display active mission and pairing code on `HomeScreen`; provide create/join mission dialog | M2 | Survey Explorer 2 |
| 8 | Real Android Location Provider | Implement `RescueLocationProvider` via `FusedLocationProviderClient` + `LocationManager.GPS_PROVIDER` | M3 | Survey Explorer 2 |
| 9 | Observation Location Integration | Remove `mockGpsActive` in `ObservationScreen` and integrate real location with fallback to null/UNKNOWN | M3 | Survey Explorer 2 |
| 10 | Remove Map Seeding in Prod | Remove `seedSampleMap()` from `RescueMeshApp.onCreate()` | M3 | Survey Explorer 2 |
| 11 | MapScreen Tactical Empty State | Render empty-state UI card on `MapScreen` when `nodes.isEmpty()` | M3 | Survey Explorer 2 |
| 12 | Dynamic Map Coordinate Projection | Implement dynamic coordinate projection replacing hardcoded N1..N7 offsets | M3 | Survey Explorer 2 |
| 13 | Photo Evidence Capture Contract | Implement `PhotoEvidenceManager` using `ActivityResultContracts.TakePicture()` and app-private storage | M4 | Survey Explorer 2 |
| 14 | Evidence File Cancellation Cleanup | Immediately delete temporary image file upon camera cancellation/failure without creating placeholders | M4 | Survey Explorer 2 |
| 15 | Cryptographic SHA-256 Evidence Hashing | Compute SHA-256 hex digest and store immutably in `ObservationEntity.evidenceHash` | M4 | Survey Explorer 2 |
| 16 | ObservationScreen Camera UI | Add camera capture button, image thumbnail, and SHA-256 badge to `ObservationScreen` | M4 | Survey Explorer 2 |
| 17 | Safety Invariant Terminology Cleanup | Eliminate all occurrences of "SAFE" / `StatusSafe` / `GreenSafe` / `green_safe` in code, theme, and UI | M4 | Survey Explorer 2 |
| 18 | Route Warning Verification | Update `RoutePlanner.kt` to include `VERIFIED_BLOCKED` in route warnings check | M4 | Survey Explorer 2 |
| 19 | Genuine Mission Handshake | Pass active `missionId` into `NearbyMeshTransport` and transmit in `SyncMessage.Hello` | M5 | Survey Explorer 3 |
| 20 | Handshake Mission Isolation | Reject and disconnect peers whose `SyncMessage.Hello.missionId` does not match active mission | M5 | Survey Explorer 2 & 3 |
| 21 | Outbox Crash Recovery | Add `resetOrphanedSendingToPending()` in `OutboxDao` and `FakeOutboxDao`; call on `SyncCoordinator.start()` | M5 | Survey Explorer 3 |
| 22 | Outbox Exponential Backoff Retry | Implement bounded exponential backoff retry loop ($1\text{s} \to 2\text{s} \dots 32\text{s}$) in `SyncCoordinator` | M5 | Survey Explorer 3 |
| 23 | Duplicate Suppression ACK Fix | Ensure duplicate `EventBatch` transmissions return `EventAck` to prevent sender retry exhaustion | M5 | Survey Explorer 3 |
| 24 | Force Sync Digest Broadcast | Wire `MainActivity.kt` force sync action to trigger mesh state digest broadcast | M5 | Survey Explorer 3 |
| 25 | Transport Telemetry Instrumentation | Track received bytes, connection latency, queue depth in `NearbyMeshTransport` and `MetricsDao` | M5 | Survey Explorer 3 |
| 26 | MetricsScreen Dashboard Enhancements | Display queue depth and delivery success rate cards on `MetricsScreen` | M5 | Survey Explorer 3 |
| 27 | Final E2E Test Suite & Adversarial Hardening | Pass 100% of Tiers 1-4 E2E tests, execute Tier 5 adversarial hardening, verify 0 regressions across all 116 tests | M6 | Survey Explorer 3 |

---

## Milestones

| # | Name | Scope | Dependencies | Status |
|---|---|---|---|---|
| M1 | Build Health, Packaging & Platform Foundations | Gradle lint config under JDK 25, AndroidManifest permissions (ACCESS_NETWORK_STATE), FileProvider setup, and `file_paths.xml` | None | DONE (Gate PASS: APK 18.1MB, lintDebug exit 0, 116/116 tests pass) |
| M2 | Mission Lifecycle & Schema Foundation | `MissionEntity` pairingCode, `MissionDao`, Room database wiring, active mission persistence, HomeScreen mission UI & pairing dialog | M1 | DONE (Gate PASS: 126/126 unit tests, 0 torn reads) |
| M3 | Data Integrity, Real Location & Map Cleanliness | `RescueLocationProvider` (Fused + GPS fallback), real location in `ObservationScreen` with null/UNKNOWN handling, remove `seedSampleMap` from prod `onCreate`, MapScreen empty state & dynamic projection | M1, M2 | DONE (Gate PASS: 152/152 unit tests, zero fake stubs) |
| M4 | Evidence Capture, SHA-256 Hashing & Route Safety | `PhotoEvidenceManager` (TakePicture, filesDir/evidence/), `ObservationEntity.evidenceHash`, ObservationScreen camera UI, eliminate "safe" terminology across code/theme/UI, RoutePlanner warnings | M1, M2, M3 | DONE (Gate PASS: 198/198 unit tests, VICTORY CONFIRMED) |
| M5 | Offline Mesh Handshake, Outbox Resilience & Telemetry | Genuine `missionId` in Hello, peer handshake rejection on mismatch, outbox crash recovery, bounded exponential backoff retry, duplicate suppression ACK fix, transport telemetry instrumentation | M1, M2, M3, M4 | DONE (Gate PASS: 224/224 unit tests, APK 18.47MB) |
| M6 | Final E2E Verification & Adversarial Hardening | Pass 100% of E2E test suite (Tiers 1-4), Tier 5 adversarial coverage hardening, Verification Gates 1-6 compliance | M1, M2, M3, M4, M5 | READY TO START |

---

## Interface Contracts

### 1. `LocationProvider` Contract
```kotlin
package com.rescuemesh.ai.location

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

### 2. `PhotoEvidenceManager` Contract
```kotlin
package com.rescuemesh.ai.evidence

import android.net.Uri
import java.io.File

interface EvidenceManager {
    fun createEvidenceUri(): Pair<Uri, File>
    fun computeSha256(file: File): String
    fun cleanupFile(file: File)
}
```

### 3. `MissionDao` Contract
```kotlin
package com.rescuemesh.ai.data.dao

import androidx.room.*
import com.rescuemesh.ai.data.entity.MissionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MissionDao {
    @Query("SELECT * FROM missions WHERE isActive = 1 LIMIT 1")
    fun observeActiveMission(): Flow<MissionEntity?>

    @Query("SELECT * FROM missions WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveMission(): MissionEntity?

    @Query("SELECT * FROM missions ORDER BY createdAt DESC")
    suspend fun getAllMissions(): List<MissionEntity>

    @Query("SELECT * FROM missions WHERE pairingCode = :code LIMIT 1")
    suspend fun getMissionByPairingCode(code: String): MissionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMission(mission: MissionEntity)

    @Query("UPDATE missions SET isActive = CASE WHEN missionId = :missionId THEN 1 ELSE 0 END")
    suspend fun setActiveMission(missionId: String)
}
```

### 4. `OutboxDao` Crash Recovery Contract
```kotlin
// In OutboxDao and FakeOutboxDao:
@Query("UPDATE outbox SET status = 'PENDING' WHERE status = 'SENDING'")
suspend fun resetOrphanedSendingToPending(): Int
```

---

## Code Layout
```
com.rescuemesh.ai/
├── RescueMeshApp.kt           # Dynamic active mission container & DI initialization
├── MainActivity.kt            # Android permissions & Compose navigation entry
├── data/
│   ├── database/RescueMeshDatabase.kt  # Room database with MissionDao, version bump
│   ├── entity/                # Room entities: MissionEntity (with pairingCode), ObservationEntity (with evidenceHash)
│   ├── dao/                   # MissionDao, OutboxDao (with crash recovery), ObservationDao, MapDao, PeerDao, MetricsDao
│   └── repository/            # ObservationRepository, MapRepository, SyncRepository
├── location/                  # RescueLocationProvider, AndroidRescueLocationProvider, FakeLocationProvider
├── evidence/                  # PhotoEvidenceManager (TakePicture, SHA-256 hashing)
├── intelligence/
│   ├── PriorityScheduler.kt   # Value-of-Information (VoI) scoring engine ($P \ge 0.80$ floor)
│   ├── MapFusionEngine.kt     # Conflict-preserving state machine (VERIFIED_CLEAR / VERIFIED_BLOCKED)
│   └── RoutePlanner.kt        # Dijkstra pathfinder (NEVER 'safe', excludes blocked passages)
├── mesh/
│   ├── MeshTransport.kt       # Transport interface & NearbyMeshTransport (handshake with missionId)
│   └── SyncCoordinator.kt     # Outbox supervisor loop, crash recovery, duplicate ACK fix
├── model/
│   └── SyncMessage.kt         # Sealed class protocol messages (Hello with missionId)
└── ui/
    ├── screens/               # HomeScreen (mission selector), ObservationScreen (camera + real GPS), MapScreen (empty state), PeersScreen, MetricsScreen
    └── theme/                 # Dark tactical palette (Color, Theme, Type - zero 'safe' terminology)
```
