package com.rescuemesh.ai.benchmark

import kotlinx.serialization.Serializable

/**
 * Configuration parameters for multi-round empirical scheduler benchmarks.
 */
@Serializable
data class BenchmarkRunConfig(
    val nodeCount: Int = 5,
    val byteBudgetPerWindow: Int = 500,
    val rounds: Int = 10,
    val schedulerTypes: List<String> = listOf("VOI", "FIFO", "RANDOM"),
    val routineNotesCount: Int = 8,
    val packetLossRate: Double = 0.0
)

/**
 * Empirical metrics captured during scheduler benchmark runs.
 *
 * Adheres strictly to:
 * - Invariant 4: Routes NEVER described as "safe" in code, comments, metrics, or logs.
 */
@Serializable
data class BenchmarkMetrics(
    val schedulerType: String,
    val avgHazardPropagationRounds: Double,
    val avgHazardPropagationTimeMs: Double,
    val mapConvergenceRounds: Double,
    val totalPayloadBytesTransferred: Long,
    val usefulObservationsPropagated: Int,
    val bandwidthEfficiencyRatio: Double
)

/**
 * Comprehensive benchmark suite result holding metrics across all evaluated schedulers.
 */
@Serializable
data class BenchmarkSuiteResult(
    val config: BenchmarkRunConfig,
    val metricsByScheduler: Map<String, BenchmarkMetrics>,
    val timestamp: Long = System.currentTimeMillis()
)
