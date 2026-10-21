package com.rescuemesh.ai.benchmark

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Unit and integration tests for modular benchmark package:
 * - BenchmarkMetrics
 * - BenchmarkRunner
 * - BenchmarkReportFormatter
 *
 * Verifies mathematical properties, invariant compliance, and report generation.
 */
class BenchmarkRunnerTest {

    @Test
    fun testMultiRoundBenchmarkRunnerExecution() {
        val config = BenchmarkRunConfig(
            nodeCount = 5,
            byteBudgetPerWindow = 500,
            rounds = 5,
            schedulerTypes = listOf("VOI", "FIFO", "RANDOM"),
            routineNotesCount = 8
        )

        val runner = BenchmarkRunner(config)
        val result = runner.runBenchmark()

        assertEquals(3, result.metricsByScheduler.size)

        val voi = result.metricsByScheduler["VOI"]
        val fifo = result.metricsByScheduler["FIFO"]
        val random = result.metricsByScheduler["RANDOM"]

        assertNotNull(voi)
        assertNotNull(fifo)
        assertNotNull(random)

        // Acceptance Criteria: T_hazard(VoI) < T_hazard(FIFO)
        assertTrue(
            "T_hazard(VoI) [${voi!!.avgHazardPropagationRounds}] must be strictly less than T_hazard(FIFO) [${fifo!!.avgHazardPropagationRounds}]",
            voi.avgHazardPropagationRounds < fifo.avgHazardPropagationRounds
        )

        // VoI should propagate hazard deterministically in round 1
        assertEquals(1.0, voi.avgHazardPropagationRounds, 1e-3)

        // Bandwidth efficiency: VoI > FIFO
        assertTrue(
            "VoI efficiency [${voi.bandwidthEfficiencyRatio}] must exceed FIFO [${fifo.bandwidthEfficiencyRatio}]",
            voi.bandwidthEfficiencyRatio > fifo.bandwidthEfficiencyRatio
        )

        // Map convergence: VoI <= FIFO
        assertTrue(
            "VoI convergence [${voi.mapConvergenceRounds}] should be <= FIFO [${fifo.mapConvergenceRounds}]",
            voi.mapConvergenceRounds <= fifo.mapConvergenceRounds
        )

        // Generate and verify report
        val markdown = BenchmarkReportFormatter.formatMarkdown(result)
        assertTrue(markdown.contains("RescueMesh AI — Empirical Multi-Round Scheduler Benchmark Report"))
        assertTrue(markdown.contains("VOI"))
        assertTrue(markdown.contains("FIFO"))
        assertTrue(markdown.contains("RANDOM"))

        // Strictly verify Invariant 4 compliance
        BenchmarkReportFormatter.assertNoForbiddenLanguage(markdown)

        // JSON formatting check
        val json = BenchmarkReportFormatter.formatJson(result)
        assertTrue(json.contains("\"schedulerType\": \"VOI\""))
        assertTrue(json.contains("\"nodeCount\": 5"))

        // Write report to project root
        val reportFile = File("D:/rescuemesh-ai/BENCHMARK_REPORT.md")
        BenchmarkReportFormatter.writeReport(result, reportFile)
        assertTrue(reportFile.exists())
        assertTrue(reportFile.length() > 0)
    }

    @Test
    fun testInvariant4LanguageScannerRejectsForbiddenPhrases() {
        val compliantText = "This optimal route has surveyed distance of 50m with warning flags for unverified segments."
        BenchmarkReportFormatter.assertNoForbiddenLanguage(compliantText)

        val invalidText = "Take the safe route through the lobby."
        var caught = false
        try {
            BenchmarkReportFormatter.assertNoForbiddenLanguage(invalidText)
        } catch (e: IllegalStateException) {
            caught = true
        }
        assertTrue("Formatter must throw on forbidden route language", caught)
    }
}
