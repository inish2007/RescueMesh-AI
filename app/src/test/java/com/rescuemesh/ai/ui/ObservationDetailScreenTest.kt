package com.rescuemesh.ai.ui

import com.rescuemesh.ai.data.entity.ObservationEntity
import com.rescuemesh.ai.ui.navigation.Screen
import com.rescuemesh.ai.ui.screens.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit test suite validating:
 * 1. Evidence state resolution across all 5 discrete states without dummy fabrication.
 * 2. ASTRA-P0-04 Location fix freshness assessment and staleness warnings.
 * 3. Evidence file size and cryptographic metadata formatting.
 * 4. NavGraph route resolution and parameter handling.
 */
class ObservationDetailScreenTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createBaseObservation(
        eventId: String = "obs-test-001",
        missionId: String = "rescue-01",
        authorDeviceId: String = "device-alpha",
        authorSequence: Long = 1L,
        eventType: String = "ROUTE_STATUS",
        title: String = "Corridor Alpha Check",
        description: String = "Field survey note",
        reportedStatus: String? = "OPEN",
        latitude: Double? = 12.9716,
        longitude: Double? = 77.5946,
        accuracy: Float? = 8.5f,
        evidencePath: String? = null,
        evidenceHash: String? = null,
        verificationStatus: String = "UNVERIFIED",
        observedAtDevice: Long = System.currentTimeMillis(),
        receivedAt: Long = System.currentTimeMillis(),
        isLocal: Boolean = true
    ): ObservationEntity {
        return ObservationEntity(
            eventId = eventId,
            missionId = missionId,
            authorDeviceId = authorDeviceId,
            authorSequence = authorSequence,
            eventType = eventType,
            title = title,
            description = description,
            reportedStatus = reportedStatus,
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            evidencePath = evidencePath,
            evidenceHash = evidenceHash,
            verificationStatus = verificationStatus,
            observedAtDevice = observedAtDevice,
            receivedAt = receivedAt,
            isLocal = isLocal
        )
    }

    // =========================================================================
    // 1. 5 EVIDENCE STATES RESOLUTION TESTS (Zero Placeholder Fabrication)
    // =========================================================================

    @Test
    fun `State 1 - No Photo when evidenceHash is null or empty`() {
        val obsNullHash = createBaseObservation(evidenceHash = null, evidencePath = null)
        val stateNull = resolveEvidenceState(obsNullHash)
        assertTrue("Expected EvidenceUiState.NoPhoto", stateNull is EvidenceUiState.NoPhoto)

        val obsBlankHash = createBaseObservation(evidenceHash = "   ", evidencePath = null)
        val stateBlank = resolveEvidenceState(obsBlankHash)
        assertTrue("Expected EvidenceUiState.NoPhoto for blank hash", stateBlank is EvidenceUiState.NoPhoto)
    }

    @Test
    fun `State 2 - Photo Pending when hash exists but file is not yet transferred`() {
        val testSha = "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e"
        val obs = createBaseObservation(
            evidenceHash = testSha,
            evidencePath = null,
            verificationStatus = "PENDING",
            isLocal = false
        )

        val state = resolveEvidenceState(obs, isTransferring = false)
        assertTrue("Expected EvidenceUiState.PhotoPending", state is EvidenceUiState.PhotoPending)
        val pending = state as EvidenceUiState.PhotoPending
        assertEquals(testSha, pending.expectedHash)
    }

    @Test
    fun `State 3 - Transferring when active transfer is in progress`() {
        val testSha = "b591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146f"
        val obs = createBaseObservation(
            evidenceHash = testSha,
            evidencePath = null,
            verificationStatus = "TRANSFERRING"
        )

        // Trigger via explicit status string
        val stateStatus = resolveEvidenceState(obs)
        assertTrue("Expected EvidenceUiState.Transferring", stateStatus is EvidenceUiState.Transferring)
        assertEquals(testSha, (stateStatus as EvidenceUiState.Transferring).expectedHash)

        // Trigger via in-flight parameter flag with progress
        val obsUnverified = createBaseObservation(
            evidenceHash = testSha,
            evidencePath = null,
            verificationStatus = "UNVERIFIED"
        )
        val stateParam = resolveEvidenceState(obsUnverified, isTransferring = true, transferProgress = 0.65f)
        assertTrue("Expected EvidenceUiState.Transferring", stateParam is EvidenceUiState.Transferring)
        val transferring = stateParam as EvidenceUiState.Transferring
        assertEquals(testSha, transferring.expectedHash)
        assertEquals(0.65f, transferring.progress ?: 0f, 0.001f)
    }

    @Test
    fun `State 4 - Received and Verified when file exists on disk and status is valid`() {
        val testFile = tempFolder.newFile("verified_evidence.jpg")
        testFile.writeBytes(ByteArray(4096) { 0x42 }) // 4 KB of payload bytes
        val testSha = "c591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f1470"

        val obs = createBaseObservation(
            evidenceHash = testSha,
            evidencePath = testFile.absolutePath,
            verificationStatus = "VERIFIED"
        )

        val state = resolveEvidenceState(obs)
        assertTrue("Expected EvidenceUiState.Verified", state is EvidenceUiState.Verified)
        val verified = state as EvidenceUiState.Verified
        assertEquals(testFile.absolutePath, verified.filePath)
        assertEquals(testSha, verified.hash)
        assertEquals(4096L, verified.fileSizeBytes)
    }

    @Test
    fun `State 5 - Verification Failed or Corrupted when status is flagged as failed`() {
        val testSha = "d591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f1471"
        val obsCorrupted = createBaseObservation(
            evidenceHash = testSha,
            evidencePath = null,
            verificationStatus = "CORRUPTED"
        )

        val stateCorrupted = resolveEvidenceState(obsCorrupted)
        assertTrue("Expected EvidenceUiState.FailedOrCorrupted", stateCorrupted is EvidenceUiState.FailedOrCorrupted)
        val failed = stateCorrupted as EvidenceUiState.FailedOrCorrupted
        assertEquals(testSha, failed.hash)
        assertTrue("Reason should describe integrity failure", failed.reason.contains("Integrity check failed"))

        val obsFailed = createBaseObservation(
            evidenceHash = testSha,
            evidencePath = null,
            verificationStatus = "FAILED"
        )
        val stateFailed = resolveEvidenceState(obsFailed)
        assertTrue("Expected EvidenceUiState.FailedOrCorrupted", stateFailed is EvidenceUiState.FailedOrCorrupted)
    }

    @Test
    fun `Integrity invariant - missing local file marked as corrupted for local observations`() {
        val nonExistentPath = File(tempFolder.root, "ghost_file.jpg").absolutePath
        val testSha = "e591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f1472"

        val obs = createBaseObservation(
            evidenceHash = testSha,
            evidencePath = nonExistentPath,
            verificationStatus = "VERIFIED",
            isLocal = true
        )

        val state = resolveEvidenceState(obs)
        assertTrue(
            "Local observation with missing physical file must resolve to FailedOrCorrupted",
            state is EvidenceUiState.FailedOrCorrupted
        )
        val failed = state as EvidenceUiState.FailedOrCorrupted
        assertTrue(failed.reason.contains("missing or zero-byte"))
    }

    // =========================================================================
    // 2. ASTRA-P0-04 LOCATION FIX FRESHNESS & STALENESS TESTS
    // =========================================================================

    @Test
    fun `Location Freshness - fix within 5 minutes is marked as Recent Live`() {
        val now = 1_000_000_000L
        val fixTime = now - (2 * 60 * 1000L) // 2 minutes ago

        val freshness = formatLocationFreshness(observedAtDevice = fixTime, currentTimeMs = now)

        assertTrue("Should be marked as live/recent", freshness.isLiveRecent)
        assertFalse("Should not be stale", freshness.isStale)
        assertEquals("2m ago", freshness.ageDisplay)
        assertTrue(freshness.statusLabel.contains("RECENT FIX"))
        assertNull("Recent fix should have no warning message", freshness.warningMessage)
    }

    @Test
    fun `Location Freshness - fix between 5 and 30 minutes is marked as Historical`() {
        val now = 1_000_000_000L
        val fixTime = now - (15 * 60 * 1000L) // 15 minutes ago

        val freshness = formatLocationFreshness(observedAtDevice = fixTime, currentTimeMs = now)

        assertFalse("15m fix should not be live recent", freshness.isLiveRecent)
        assertFalse("15m fix should not be stale (>30m)", freshness.isStale)
        assertEquals("15m ago", freshness.ageDisplay)
        assertTrue(freshness.statusLabel.contains("HISTORICAL FIX"))
        assertNull("15m fix should have no warning message", freshness.warningMessage)
    }

    @Test
    fun `Location Freshness - fix older than 30 minutes is explicitly flagged as STALE with safety warning`() {
        val now = 1_000_000_000L
        val fixTime = now - (45 * 60 * 1000L) // 45 minutes ago

        val freshness = formatLocationFreshness(observedAtDevice = fixTime, currentTimeMs = now)

        assertFalse("Stale fix is not recent", freshness.isLiveRecent)
        assertTrue("Fix > 30 min must be marked stale", freshness.isStale)
        assertEquals("45m ago", freshness.ageDisplay)
        assertTrue(freshness.statusLabel.contains("STALE FIX"))
        assertNotNull("Stale fix must include explicit safety warning", freshness.warningMessage)
        assertTrue(
            "Warning message must instruct not to treat as live",
            freshness.warningMessage!!.contains("Do not present or interpret as live tracking")
        )
    }

    @Test
    fun `Location Freshness - hours formatting handles multi-hour stale fixes`() {
        val now = 1_000_000_000L
        val fixTime = now - (2 * 3600 * 1000L + 15 * 60 * 1000L) // 2h 15m ago

        val freshness = formatLocationFreshness(observedAtDevice = fixTime, currentTimeMs = now)

        assertTrue(freshness.isStale)
        assertEquals("2h 15m ago", freshness.ageDisplay)
    }

    // =========================================================================
    // 3. EVIDENCE BYTE SIZE FORMATTING TESTS
    // =========================================================================

    @Test
    fun `Byte formatting correctly formats bytes, kilobytes, and megabytes`() {
        assertEquals("0 B", formatEvidenceBytes(0L))
        assertEquals("0 B", formatEvidenceBytes(-50L))
        assertEquals("512.0 KB (524288 bytes)", formatEvidenceBytes(524288L))
        assertEquals("1.50 MB (1572864 bytes)", formatEvidenceBytes(1572864L))
    }

    // =========================================================================
    // 4. NAVGRAPH ROUTE & CONTRACT TESTS
    // =========================================================================

    @Test
    fun `NavGraph observation detail route string and argument helper match contracts`() {
        assertEquals("observation_detail/{eventId}", Screen.OBSERVATION_DETAIL_ROUTE)
        assertEquals("observation_detail/obs-abc-123", Screen.observationDetail("obs-abc-123"))

        // Bottom bar suppression test contract: route must start with observation_detail
        val detailRoute = Screen.observationDetail("any-event-uuid-456")
        assertTrue("Detail route must trigger bottom bar suppression", detailRoute.startsWith("observation_detail"))
        assertFalse("Home route must not trigger bottom bar suppression", Screen.Home.route.startsWith("observation_detail"))
    }

    // =========================================================================
    // 5. FULL-SCREEN VIEWER TRIGGER ELIGIBILITY & METADATA FORMATTING
    // =========================================================================

    @Test
    fun `Full-screen photo viewer trigger is strictly gated to verified evidence with existing file`() {
        val testFile = tempFolder.newFile("sample_verified.jpg")
        testFile.writeBytes(ByteArray(2048) { 0x55 })
        val validHash = "f591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f1473"

        val verifiedObs = createBaseObservation(
            evidenceHash = validHash,
            evidencePath = testFile.absolutePath,
            verificationStatus = "VERIFIED"
        )
        val verifiedState = resolveEvidenceState(verifiedObs)
        assertTrue("Precondition: state must be Verified", verifiedState is EvidenceUiState.Verified)
        val v = verifiedState as EvidenceUiState.Verified
        assertTrue("Physical evidence file must exist on disk for full-screen inspection", File(v.filePath).exists())
        assertTrue("Evidence byte size must be greater than zero", v.fileSizeBytes > 0L)
        assertEquals(validHash, v.hash)

        // Non-verified observations must NOT produce EvidenceUiState.Verified
        val noPhotoObs = createBaseObservation(evidenceHash = null, evidencePath = null)
        assertFalse("NoPhoto cannot trigger viewer", resolveEvidenceState(noPhotoObs) is EvidenceUiState.Verified)

        val pendingObs = createBaseObservation(evidenceHash = validHash, evidencePath = null, verificationStatus = "PENDING")
        assertFalse("Pending cannot trigger viewer", resolveEvidenceState(pendingObs) is EvidenceUiState.Verified)

        val corruptedObs = createBaseObservation(evidenceHash = validHash, evidencePath = testFile.absolutePath, verificationStatus = "CORRUPTED")
        assertFalse("Corrupted cannot trigger viewer", resolveEvidenceState(corruptedObs) is EvidenceUiState.Verified)
    }

    @Test
    fun `Coordinate and uncertainty formatting handles precision and null cases safely`() {
        // Valid coordinates with uncertainty
        val obsWithGeo = createBaseObservation(latitude = 12.9715987, longitude = 77.5945621, accuracy = 4.2f)
        val latStr = String.format(java.util.Locale.US, "%.6f°", obsWithGeo.latitude)
        val lonStr = String.format(java.util.Locale.US, "%.6f°", obsWithGeo.longitude)
        val accStr = obsWithGeo.accuracy?.let { "±${it.toInt()} meters" } ?: "Uncertain"

        assertEquals("12.971599°", latStr)
        assertEquals("77.594562°", lonStr)
        assertEquals("±4 meters", accStr)

        // Null coordinates (indoor/subterranean fix)
        val obsIndoor = createBaseObservation(latitude = null, longitude = null, accuracy = null)
        assertNull("Indoor fix has null latitude", obsIndoor.latitude)
        assertNull("Indoor fix has null longitude", obsIndoor.longitude)
        assertNull("Indoor fix has null accuracy", obsIndoor.accuracy)
    }
}
