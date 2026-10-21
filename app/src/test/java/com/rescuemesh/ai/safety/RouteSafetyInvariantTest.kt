package com.rescuemesh.ai.safety

import com.rescuemesh.ai.data.entity.MapEdgeEntity
import com.rescuemesh.ai.data.entity.MapNodeEntity
import com.rescuemesh.ai.fakes.FakeMapDao
import com.rescuemesh.ai.intelligence.RoutePlanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Level 5 Security Review & Architectural Safety Invariant Test Suite.
 *
 * Verifies:
 * 1. Dynamic Routing Invariant: Routes, warnings, and path labels are NEVER described as "safe"
 *    under any map topology (clear, conflicted, unverified, or blocked).
 * 2. Blocked Edge Exclusion: Passages marked REPORTED_BLOCKED or VERIFIED_BLOCKED are strictly excluded.
 * 3. Conflicted Edge Handling: CONFLICTED edges receive risk penalties and explicit warnings, never deemed safe.
 * 4. Static Codebase Audit: Exhaustive scan of app/src/main/ to ensure zero occurrences of prohibited
 *    tokens like 'StatusSafe', 'GreenSafe', 'green_safe', 'safe route', 'safe path', etc.
 * 5. FileProvider Security Audit: Validates AndroidManifest.xml and file_paths.xml for app-private scoping
 *    and URI grant permission security.
 */
class RouteSafetyInvariantTest {

    // =========================================================================
    // 1. Dynamic RoutePlanner Safety Invariant Tests
    // =========================================================================

    @Test
    fun testRoutePlannerNeverLabelsRouteOrWarningsAsSafe() = runBlocking {
        val testMapDao = FakeMapDao()
        val missionId = "MISSION_SAFETY_TEST"

        // Setup nodes
        val nA = MapNodeEntity(nodeId = "node_A", missionId = missionId, label = "Sector Alpha Entrance", latitude = 0.0, longitude = 0.0)
        val nB = MapNodeEntity(nodeId = "node_B", missionId = missionId, label = "Sector Alpha Corridor", latitude = 0.001, longitude = 0.001)
        val nC = MapNodeEntity(nodeId = "node_C", missionId = missionId, label = "Sector Alpha Command", latitude = 0.002, longitude = 0.002)
        testMapDao.insertNodes(listOf(nA, nB, nC))

        // Scenario 1: All edges REPORTED_OPEN
        testMapDao.insertEdges(listOf(
            MapEdgeEntity("edge_1", missionId, "node_A", "node_B", 10f, "REPORTED_OPEN"),
            MapEdgeEntity("edge_2", missionId, "node_B", "node_C", 15f, "REPORTED_OPEN")
        ))

        val planner = RoutePlanner(testMapDao)
        val openRoute = planner.findRoute("node_A", "node_C", missionId)

        assertTrue("Route should be found", openRoute.isComplete)
        assertEquals(25.0, openRoute.totalDistance, 0.001)
        assertFalse("Route warnings must never use the word 'safe'", openRoute.warnings.any { it.contains("safe", ignoreCase = true) })
        assertFalse("Path labels must never contain 'safe'", openRoute.pathLabels.any { it.contains("safe", ignoreCase = true) })

        // Scenario 2: Intermediate edge CONFLICTED
        val testMapDao2 = FakeMapDao()
        testMapDao2.insertNodes(listOf(nA, nB, nC))
        testMapDao2.insertEdges(listOf(
            MapEdgeEntity("edge_1", missionId, "node_A", "node_B", 10f, "REPORTED_OPEN"),
            MapEdgeEntity("edge_2", missionId, "node_B", "node_C", 15f, "CONFLICTED")
        ))

        val conflictedRoute = RoutePlanner(testMapDao2).findRoute("node_A", "node_C", missionId)
        assertTrue(conflictedRoute.isComplete)
        assertTrue("Warnings must document the conflicted edge", conflictedRoute.warnings.any { it.contains("CONFLICTED") })
        assertFalse("Warnings must never describe conflicted route as 'safe'", conflictedRoute.warnings.any { it.contains("safe", ignoreCase = true) })

        // Scenario 3: Intermediate edge UNKNOWN
        val testMapDao3 = FakeMapDao()
        testMapDao3.insertNodes(listOf(nA, nB, nC))
        testMapDao3.insertEdges(listOf(
            MapEdgeEntity("edge_1", missionId, "node_A", "node_B", 10f, "UNKNOWN"),
            MapEdgeEntity("edge_2", missionId, "node_B", "node_C", 15f, "REPORTED_OPEN")
        ))

        val unknownRoute = RoutePlanner(testMapDao3).findRoute("node_A", "node_C", missionId)
        assertTrue(unknownRoute.isComplete)
        assertTrue("Warnings must document the unknown edge", unknownRoute.warnings.any { it.contains("UNKNOWN") })
        assertFalse("Warnings must never describe unverified route as 'safe'", unknownRoute.warnings.any { it.contains("safe", ignoreCase = true) })
    }

    @Test
    fun testBlockedEdgesAreStrictlyExcludedFromRouting() = runBlocking {
        val testMapDao = FakeMapDao()
        val missionId = "MISSION_BLOCKED_TEST"

        val n1 = MapNodeEntity(nodeId = "N1", missionId = missionId, label = "Start", latitude = 0.0, longitude = 0.0)
        val n2 = MapNodeEntity(nodeId = "N2", missionId = missionId, label = "Blocked Waypoint", latitude = 1.0, longitude = 1.0)
        val n3 = MapNodeEntity(nodeId = "N3", missionId = missionId, label = "Detour Waypoint", latitude = 1.0, longitude = -1.0)
        val n4 = MapNodeEntity(nodeId = "N4", missionId = missionId, label = "Goal", latitude = 2.0, longitude = 0.0)
        testMapDao.insertNodes(listOf(n1, n2, n3, n4))

        // Direct path N1->N2->N4 has shorter distance (20m) but N1->N2 is VERIFIED_BLOCKED
        testMapDao.insertEdges(listOf(
            MapEdgeEntity("e_direct_1", missionId, "N1", "N2", 10f, "VERIFIED_BLOCKED"),
            MapEdgeEntity("e_direct_2", missionId, "N2", "N4", 10f, "REPORTED_OPEN"),
            MapEdgeEntity("e_detour_1", missionId, "N1", "N3", 25f, "REPORTED_OPEN"),
            MapEdgeEntity("e_detour_2", missionId, "N3", "N4", 25f, "REPORTED_OPEN")
        ))

        val planner = RoutePlanner(testMapDao)
        val result = planner.findRoute("N1", "N4", missionId, avoidBlocked = true)

        assertTrue("Route planner must find detour around blocked passage", result.isComplete)
        assertEquals("Detour path must be taken", listOf("N1", "N3", "N4"), result.path)
        assertEquals(50.0, result.totalDistance, 0.001)
        assertFalse("Blocked edge must never be traversed", result.edgesUsed.contains("e_direct_1"))
        assertFalse("Warnings must never describe detour as 'safe'", result.warnings.any { it.contains("safe", ignoreCase = true) })
    }

    // =========================================================================
    // 2. Static Codebase Audit: Zero "Safe Route" Terminology in app/src/main/
    // =========================================================================

    @Test
    fun testStaticScanZeroSafeRouteTerminologyInAppSourceCode() {
        val mainSrcDir = File("src/main").let { if (it.exists()) it else File("app/src/main") }
        assertTrue("Main source directory must exist at ${mainSrcDir.absolutePath}", mainSrcDir.exists())

        val forbiddenRouteSafePatterns = listOf(
            Regex("(?i)\\bStatusSafe\\b"),
            Regex("(?i)\\bGreenSafe\\b"),
            Regex("(?i)\\bgreen_safe\\b"),
            Regex("(?i)\\bsafe[_-]?route\\b"),
            Regex("(?i)\\broute[_-]?safe\\b"),
            Regex("(?i)\\bsafe[_-]?path\\b"),
            Regex("(?i)\\bpath[_-]?safe\\b"),
            Regex("(?i)\\bsafest[_-]?route\\b"),
            Regex("(?i)\\bsafest[_-]?path\\b"),
            Regex("(?i)\"safe route\""),
            Regex("(?i)\"safe path\""),
            Regex("(?i)\"safest route\"")
        )

        val violations = mutableListOf<String>()

        mainSrcDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .forEach { file ->
                val lines = file.readLines()
                lines.forEachIndexed { index, line ->
                    val isDirectiveComment = line.contains("NEVER described as", ignoreCase = true) ||
                            line.contains("never describe", ignoreCase = true) ||
                            line.contains("Rules:", ignoreCase = true) ||
                            line.contains("zero 'safe' terminology", ignoreCase = true)

                    if (!isDirectiveComment) {
                        for (pattern in forbiddenRouteSafePatterns) {
                            if (pattern.containsMatchIn(line)) {
                                violations.add("${file.relativeTo(mainSrcDir).path}:${index + 1}: ${line.trim()}")
                            }
                        }
                    }
                }
            }

        assertTrue(
            "Found prohibited 'safe route' terminology violations in app source:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    // =========================================================================
    // 3. FileProvider & App-Private Storage Security Audit
    // =========================================================================

    @Test
    fun testFileProviderConfigurationSecurityAudit() {
        val manifestFile = File("src/main/AndroidManifest.xml").let {
            if (it.exists()) it else File("app/src/main/AndroidManifest.xml")
        }
        assertTrue("AndroidManifest.xml must exist", manifestFile.exists())
        val manifestContent = manifestFile.readText()

        // 1. Provider must be declared with androidx.core.content.FileProvider
        assertTrue(
            "FileProvider must be configured in AndroidManifest.xml",
            manifestContent.contains("androidx.core.content.FileProvider")
        )

        // 2. android:exported must be false to prevent external malicious invocation
        assertTrue(
            "FileProvider MUST have android:exported=\"false\"",
            manifestContent.contains("android:exported=\"false\"")
        )

        // 3. android:grantUriPermissions must be true to allow scoped temporary access to camera
        assertTrue(
            "FileProvider MUST have android:grantUriPermissions=\"true\"",
            manifestContent.contains("android:grantUriPermissions=\"true\"")
        )

        // 4. android:authorities must match \${applicationId}.fileprovider
        assertTrue(
            "FileProvider authorities must be scoped with applicationId.fileprovider",
            manifestContent.contains("\${applicationId}.fileprovider")
        )

        // 5. meta-data must reference @xml/file_paths
        assertTrue(
            "FileProvider must reference @xml/file_paths",
            manifestContent.contains("@xml/file_paths")
        )
    }

    @Test
    fun testFilePathsXmlIsStrictlyScopedToAppPrivateEvidenceDir() {
        val filePathsXml = File("src/main/res/xml/file_paths.xml").let {
            if (it.exists()) it else File("app/src/main/res/xml/file_paths.xml")
        }
        assertTrue("file_paths.xml must exist", filePathsXml.exists())
        val content = filePathsXml.readText()

        // 1. Must use <files-path> pointing to app-private internal storage
        assertTrue(
            "file_paths.xml must use <files-path> for internal storage isolation",
            content.contains("<files-path")
        )

        // 2. Path attribute must be scoped to evidence/ or evidence
        assertTrue(
            "file_paths.xml path must be scoped to 'evidence/' directory",
            content.contains("path=\"evidence/\"") || content.contains("path=\"evidence\"")
        )

        // 3. Must NOT expose <root-path> (critical security vulnerability)
        assertFalse(
            "file_paths.xml must NEVER declare <root-path>",
            content.contains("<root-path")
        )

        // 4. Must NOT expose broad external root paths without subfolder isolation
        assertFalse(
            "file_paths.xml must not declare unscoped <external-path path=\".\" />",
            content.contains("<external-path path=\".\"")
        )
    }
}
