# RescueMesh AI — Agent Directives & System Architecture

This repository contains **RescueMesh AI**, an offline-first collaborative intelligence system designed for disaster-response teams operating in network-denied environments.

All AI agents (Antigravity, Claude Code, OpenCode, Codex) operating in Orca worktrees must adhere to the rules, architectural invariants, and verification standards defined in this document.

---

## 1. System Invariants (Zero Exceptions)

1. **Strictly Offline-First**:
   - The application must operate with zero mandatory internet or cloud infrastructure.
   - Do NOT introduce dependencies on external cloud APIs, Firebase authentication, remote databases, or external telemetry.
   - All inter-device communication is strictly peer-to-peer using **Google Nearby Connections** (`P2P_CLUSTER` strategy).

2. **Durable Local-First Persistence**:
   - Every observation, status change, or receipt MUST be committed to the local Room database (`RescueMeshDatabase`) *before* any network transmission attempt.
   - Never keep state in memory-only variables that does not survive process termination or device reboot.

3. **Conflict-Preserving Map Fusion**:
   - Contradicting observations on graph edges must NEVER overwrite earlier evidence.
   - Disagreeing reports mark an edge as `CONFLICTED` and append to the conflict history.
   - A `REPORTED_BLOCKED` edge is never cleared by time; only human-verified surveys or explicit resolution can resolve conflicts.

4. **Safety-Guaranteed Route Guidance**:
   - Routes must **NEVER** be described as "safe" in code, logs, or UI.
   - Always report routes in terms of *relative cost*, *surveyed distance*, and *explicit warning flags* (e.g. unverified segments, adjacent hazards, conflicted passages).
   - BLOCKED edges are excluded by default. CONFLICTED edges incur heavy risk penalties.

5. **Priority Scheduling (Value-of-Information)**:
   - Bandwidth is severely constrained during disaster mesh operations.
   - Messages are queued in the `OutboxEntity` table and scored using the `PriorityScheduler`.
   - **Safety Override**: Urgency reports (Hazards, Blocked passages) have an absolute priority floor ($P \ge 0.8$) and must never be suppressed by aging or transmission cost penalties.

---

## 2. Directory Layout & Key Packages

```
com.rescuemesh.ai/
├── RescueMeshApp.kt           # Manual DI application container
├── MainActivity.kt            # Android permissions & Compose navigation entry
├── data/
│   ├── database/              # Room database singleton
│   ├── entity/                # 8 Room entities (Observations, Nodes, Edges, Outbox, Peers, Metrics)
│   ├── dao/                   # Room DAOs with sequence tracking & flows
│   └── repository/            # ObservationRepository, MapRepository, SyncRepository
├── intelligence/
│   ├── PriorityScheduler.kt   # Value-of-Information (VoI) scoring engine
│   ├── MapFusionEngine.kt     # Conflict-preserving graph fusion state machine
│   ├── RoutePlanner.kt        # Dijkstra pathfinder with uncertainty/risk penalties
│   └── BaselineSchedulers.kt  # FIFO and Random schedulers for scientific ablation
├── mesh/
│   ├── MeshTransport.kt       # Google Nearby Connections P2P transport wrapper
│   └── SyncCoordinator.kt     # Digest-based state synchronization protocol
├── model/
│   └── SyncMessage.kt         # Sealed class protocol messages (Hello, Digest, Batch, Ack)
└── ui/
    ├── navigation/NavGraph.kt # 5-tab Material3 bottom navigation
    ├── screens/               # HomeScreen, ObservationScreen, MapScreen, PeersScreen, MetricsScreen
    └── theme/                 # Dark tactical palette (Color, Theme, Type)
```

---

## 3. Orca Worktree Safety Rules

When operating inside an Orca worktree:
- **Worktree Boundaries**: Never reference absolute file paths outside the current worktree directory. All paths must be relative to the project root.
- **Gradle Execution**: On Windows, always invoke `./gradlew.bat` with the project-configured JVM (`org.gradle.java.home=C:/Users/K INISH KUMAR/.jdks/openjdk-25.0.1`).
- **Secret & Configuration Protection**: Never modify `local.properties` or commit credentials.

---

## 4. Verification Commands

Before proposing or merging any pull request:

```bash
# 1. Compile all Kotlin sources
cmd /c "gradlew.bat compileDebugKotlin"

# 2. Run unit tests
cmd /c "gradlew.bat testDebugUnitTest"

# 3. Lint check
cmd /c "gradlew.bat lintDebug"
```
