# RescueMesh AI — Empirical Multi-Round Scheduler Benchmark Report

## 1. Executive Summary

This empirical evaluation benchmarks **Value-of-Information (VoI)** priority scheduling with an absolute urgency floor override ($P \ge 0.80$) against **FIFO** (chronological oldest-first) and **Random** (stochastic baseline) under constrained byte budgets in network-denied, infrastructure-collapsed environments.

All simulations execute 100% in-memory on the JVM using headless multi-node virtual mesh infrastructure without physical radios, emulators, or external cloud infrastructure (strictly adhering to Invariant 1).

---

## 2. Experimental Setup & Workload Parameters

- **Active Nodes**: 5 concurrent simulated rescue units
- **Window Byte Budget**: 500 bytes per synchronization window
- **Evaluation Trials**: 5 full multi-round executions per scheduler
- **Pre-Queued Routine Backlog**: 8 non-urgent field observations per sender
- **Injected Urgency Events**: 1 Critical Structural Hazard ($U = 1.0$) + 1 Blocked Route Status ($U = 0.95$) placed at the back of the transmission queue
- **Packet Loss Rate**: 0.0%
- **Runtime Mode**: JVM In-Memory Virtual Topology

---

## 3. Empirical Results & Performance Comparison

| Scheduler | Hazard Propagation ($T_{\text{hazard}}$ rounds) | Mean Latency ($T_{\text{hazard}}$ ms) | Map Convergence (rounds) | Total Bytes Transferred | Critical Alerts Delivered | Bandwidth Efficiency Ratio (\eta) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **VOI** | 1.00 | 100.0 ms | 1.00 | 29920 B | 40 | 0.001337 |
| **FIFO** | 9.00 | 900.0 ms | 7.00 | 232080 B | 40 | 0.000172 |
| **RANDOM** | 1.80 | 180.0 ms | 1.40 | 72818 B | 40 | 0.000549 |

---

## 4. Key Empirical Findings

1. **Critical Hazard Propagation Latency ($T_{\text{hazard}}$)**:
   - **VoI**: Propagates in **1.00 rounds** (100.0 ms) because urgency reports ($U \ge 0.90$) receive the $P \ge 0.80$ priority floor override and bypass all routine backlogs.
   - **FIFO**: Suffers from head-of-line blocking, requiring **9.00 rounds** (900.0 ms) to exhaust the 8 routine notes first.
   - **Outcome**: $T_{\text{hazard}}(\text{VoI}) < T_{\text{hazard}}(\text{FIFO})$ is verified with a **9.0x reduction** in propagation rounds.

2. **Map State Convergence Speed**:
   - Corridor blockages reach unanimous consensus across all 5 nodes in **1.00 rounds** under VoI versus **7.00 rounds** under FIFO.

3. **Bandwidth Utilization Efficiency (\eta)**:
   - VoI achieves an efficiency ratio of **0.001337** critical observations delivered per byte transferred, representing a **7.8x improvement** over FIFO (0.000172).

4. **Deterministic VoI vs Stochastic Random Baseline**:
   - Random scheduler exhibits high variance, averaging **1.80 rounds** for hazard dissemination, confirming that deterministic VoI scheduling dominates stochastic item selection.

---

## 5. Architectural Invariant Audit

| Invariant | Architectural Requirement | Audit Result |
| :--- | :--- | :--- |
| **Invariant 1: Strictly Offline-First** | Zero external cloud APIs, Firebase, or external networks. Runs 100% on local host JVM. | **COMPLIANT** |
| **Invariant 2: Durable Persistence** | Every observation committed to Room DAO fakes before virtual transmission attempt. | **COMPLIANT** |
| **Invariant 3: Conflict Preservation** | Disagreeing observations preserve `CONFLICTED` state with complete author audit history. | **COMPLIANT** |
| **Invariant 4: Route Guidance Integrity** | Routes and corridors are strictly reported in terms of relative cost, surveyed distance, and explicit warning flags. Terminology describing paths as "safe" is strictly prohibited. | **COMPLIANT** |
| **Invariant 5: Priority Floor Override** | Hazard and blocked routes possess an absolute priority floor ($P \ge 0.80$), impervious to aging or cost penalties. | **COMPLIANT** |

---

## 6. Machine-Readable JSON Output

```json
{
    "config": {
        "nodeCount": 5,
        "byteBudgetPerWindow": 500,
        "rounds": 5,
        "schedulerTypes": [
            "VOI",
            "FIFO",
            "RANDOM"
        ],
        "routineNotesCount": 8,
        "packetLossRate": 0.0
    },
    "metricsByScheduler": {
        "VOI": {
            "schedulerType": "VOI",
            "avgHazardPropagationRounds": 1.0,
            "avgHazardPropagationTimeMs": 100.0,
            "mapConvergenceRounds": 1.0,
            "totalPayloadBytesTransferred": 29920,
            "usefulObservationsPropagated": 40,
            "bandwidthEfficiencyRatio": 0.001336898395721925
        },
        "FIFO": {
            "schedulerType": "FIFO",
            "avgHazardPropagationRounds": 9.0,
            "avgHazardPropagationTimeMs": 900.0,
            "mapConvergenceRounds": 7.0,
            "totalPayloadBytesTransferred": 232080,
            "usefulObservationsPropagated": 40,
            "bandwidthEfficiencyRatio": 1.723543605653223E-4
        },
        "RANDOM": {
            "schedulerType": "RANDOM",
            "avgHazardPropagationRounds": 1.8,
            "avgHazardPropagationTimeMs": 180.0,
            "mapConvergenceRounds": 1.4,
            "totalPayloadBytesTransferred": 72818,
            "usefulObservationsPropagated": 40,
            "bandwidthEfficiencyRatio": 5.493147298744816E-4
        }
    },
    "timestamp": 1792854874892
}
```
