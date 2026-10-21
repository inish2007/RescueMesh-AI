# RescueMesh AI - Knowledge Graph Report

*Generated: 2026-10-20* | *Project: RescueMesh AI* | *Nodes: 41* | *Edges: 66*

---

## God Nodes

| Node | Type | Community | Connections | Importance |
|------|------|-----------|-------------|------------|
| RescueMeshDatabase | RoomDatabase | Data Layer | 6 | 10 |
| ObservationEntity | Room Entity | Data Layer | 12 | 10 |
| PriorityScheduler | VoI Engine | Intelligence | 12 | 10 |
| MapFusionEngine | Conflict Engine | Intelligence | 12 | 10 |
| SyncMessage | Protocol Sealed Class | Protocol | 12 | 10 |
| NearbyMeshTransport | Google Nearby P2P | Protocol | 10 | 10 |
| SyncCoordinator | Sync State Machine | Protocol | 10 | 10 |
| RescueMeshApp | Application Container | UI | 10 | 9 |
| VirtualMeshEngineTest | E2E Tests | Testing | 10 | 10 |
| EmpiricalBenchmarkTest | E2E Tests | Testing | 10 | 10 |

## Surprising Connections

1. ObservationEntity is the central data node (12 edges) - interfaces with database, schedulers, fusion engine, sync coordinator.
2. PriorityScheduler and MapFusionEngine are structurally symmetric - both coordinate between observation data and persistence layers.
3. VirtualMeshEnvironment (test) has same role as NearbyMeshTransport (production) - both implement MeshTransport.
4. RescueMeshApp (DI container) connects all repositories, engines, and mesh components.
5. EmpiricalBenchmarkTest and VirtualMeshEngineTest form E2E testing backbone, validating entire protocol stack.

## Suggested Questions

1. How do the 5 architectural invariants constrain each component?
2. How does SyncCoordinator orchestrate protocol lifecycle (Hello -> Digest -> EventRequest -> EventBatch -> EventAck -> relay)?
3. How does VoI scoring formula incorporate urgency, novelty, information gain, relevance, cost, and staleness?
4. How does MapFusionEngine resolve conflicts while preserving full audit trail?
5. How does RoutePlanner integrate Dijkstra with risk/uncertainty penalties for safe route guidance?

## Graph Structure

- 41 Nodes across 5 communities (Data Layer, Intelligence, Protocol, UI, Testing)
- 66 Edges representing 12 relationship types (contains, reads, writes, uses, implements, scores, etc.)
- 5 Community clusters: Data Persistence (11), Intelligence (4), Transport/Protocol (8), UI (10), Testing (8)
- 4 Top importance hubs: RescueMeshDatabase, ObservationEntity, PriorityScheduler, SyncCoordinator
- All architectural invariants verified COMPLIANT across the graph.
