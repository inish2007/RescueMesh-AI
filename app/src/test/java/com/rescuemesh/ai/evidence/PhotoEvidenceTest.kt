package com.rescuemesh.ai.evidence

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Unit test suite for RescueMesh AI photo evidence capture and cryptographic SHA-256 integrity.
 * Validates Requirement R1, Features 13 & 14.
 */
class PhotoEvidenceTest {

    private lateinit var tempDir: File
    private lateinit var evidenceManager: FakeEvidenceManager

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "rescuemesh_test_${UUID.randomUUID().toString().take(8)}")
        evidenceManager = FakeEvidenceManager(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // 1. NIST SHA-256 Test Vectors
    // =========================================================================

    /**
     * NIST FIPS 180-4 standard verification:
     * Empty byte sequence SHA-256 hex digest MUST be:
     * e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
     */
    @Test
    fun testNistEmptyByteVectorSha256() {
        val emptyBytes = ByteArray(0)
        val computedEmptySha256 = evidenceManager.computeSha256(emptyBytes)

        assertEquals(
            "NIST empty byte vector SHA-256 digest must match standard",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            computedEmptySha256
        )

        // Also test through file with explicit allowEmpty = true
        val emptyFile = File(tempDir, "empty_nist_test.jpg").apply {
            writeBytes(emptyBytes)
        }
        val fileSha256 = evidenceManager.computeSha256(emptyFile, allowEmpty = true)
        assertEquals(
            "NIST empty file SHA-256 with allowEmpty=true must match standard",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            fileSha256
        )
    }

    // =========================================================================
    // 2. Known Byte Sequence Test Vectors
    // =========================================================================

    @Test
    fun testKnownByteSequenceHashing() {
        // Known Vector 1: "The quick brown fox jumps over the lazy dog"
        val foxBytes = "The quick brown fox jumps over the lazy dog".toByteArray(Charsets.UTF_8)
        val foxFile = File(tempDir, "fox.jpg").apply { writeBytes(foxBytes) }
        val foxSha256 = evidenceManager.computeSha256(foxFile)

        assertEquals(
            "Standard quick brown fox vector must match",
            "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592",
            foxSha256
        )

        // Known Vector 2: "abc"
        val abcBytes = "abc".toByteArray(Charsets.UTF_8)
        val abcFile = File(tempDir, "abc.jpg").apply { writeBytes(abcBytes) }
        val abcSha256 = evidenceManager.computeSha256(abcFile)

        assertEquals(
            "Standard 'abc' vector must match",
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            abcSha256
        )

        // Known Vector 3: Disaster evidence payload string
        val disasterBytes = "RescueMesh AI Disaster Evidence Verification 2026".toByteArray(Charsets.UTF_8)
        val disasterFile = File(tempDir, "disaster.jpg").apply { writeBytes(disasterBytes) }
        val disasterSha256 = evidenceManager.computeSha256(disasterFile)

        val expectedSha256 = MessageDigest.getInstance("SHA-256")
            .digest(disasterBytes)
            .joinToString("") { "%02x".format(it) }

        assertEquals(
            "Disaster verification bytes must match Java MessageDigest",
            expectedSha256,
            disasterSha256
        )
        assertEquals("SHA-256 hex string must be exactly 64 characters", 64, disasterSha256.length)
        assertTrue("SHA-256 hex string must consist of lowercase hexadecimal characters", disasterSha256.matches(Regex("^[0-9a-f]{64}$")))
    }

    // =========================================================================
    // 3. Streaming 8KB Buffer Validation for Large Files
    // =========================================================================

    @Test
    fun testStreamingCalculationWithLargeFile() {
        // Create 64 KB of repeated non-trivial bytes to exercise multiple 8KB buffer cycles
        val largeBytes = ByteArray(65536) { index -> (index % 251).toByte() }
        val largeFile = File(tempDir, "large_photo.jpg").apply { writeBytes(largeBytes) }

        val computedHash = evidenceManager.computeSha256(largeFile)

        val directDigest = MessageDigest.getInstance("SHA-256")
        val expectedHash = directDigest.digest(largeBytes).joinToString("") { "%02x".format(it) }

        assertEquals("Multi-buffer streaming SHA-256 must match single-shot digest", expectedHash, computedHash)
    }

    // =========================================================================
    // 4. File Validation & Error Handling
    // =========================================================================

    @Test
    fun testZeroByteFileThrowsIllegalArgumentExceptionByDefault() {
        val zeroByteFile = File(tempDir, "zero_byte.jpg").apply {
            writeBytes(ByteArray(0))
        }

        try {
            evidenceManager.computeSha256(zeroByteFile)
            fail("computeSha256 must throw IllegalArgumentException for zero-byte file by default")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Exception message must specify empty file",
                e.message?.contains("empty", ignoreCase = true) == true
            )
        }
    }

    @Test
    fun testNonExistentFileThrowsIllegalArgumentException() {
        val nonExistent = File(tempDir, "ghost_${UUID.randomUUID()}.jpg")
        assertFalse(nonExistent.exists())

        try {
            evidenceManager.computeSha256(nonExistent)
            fail("computeSha256 must throw IllegalArgumentException for non-existent file")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Exception message must specify file does not exist",
                e.message?.contains("exist", ignoreCase = true) == true
            )
        }
    }

    @Test
    fun testDirectoryThrowsIllegalArgumentException() {
        val subDir = File(tempDir, "sub_directory").apply { mkdirs() }
        assertTrue(subDir.isDirectory)

        try {
            evidenceManager.computeSha256(subDir)
            fail("computeSha256 must throw IllegalArgumentException when target is a directory")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Exception message must mention not a file",
                e.message?.contains("not a file", ignoreCase = true) == true ||
                        e.message?.contains("regular file", ignoreCase = true) == true
            )
        }
    }

    // =========================================================================
    // 5. Cancellation & Cleanup Validation
    // =========================================================================

    @Test
    fun testCleanupFileDeletesExistingFile() {
        val fileToClean = File(tempDir, "abandoned_capture.jpg").apply {
            writeText("dummy photo bytes")
        }
        assertTrue("Pre-condition: file must exist before cleanup", fileToClean.exists())

        val deleted = evidenceManager.cleanupFile(fileToClean)

        assertTrue("cleanupFile must return true when file was successfully deleted", deleted)
        assertFalse("File must no longer exist after cleanup", fileToClean.exists())
    }

    @Test
    fun testCleanupFileReturnsFalseForNonExistentFile() {
        val nonExistent = File(tempDir, "non_existent.jpg")
        assertFalse(nonExistent.exists())

        val result = evidenceManager.cleanupFile(nonExistent)
        assertFalse("cleanupFile must return false if file does not exist", result)
    }

    @Test
    fun testRepeatedCleanupIsIdempotent() {
        val file = File(tempDir, "double_clean.jpg").apply {
            writeText("test data")
        }

        assertTrue("First cleanup should delete file", evidenceManager.cleanupFile(file))
        assertFalse("Second cleanup should idempotently return false", evidenceManager.cleanupFile(file))
    }

    // =========================================================================
    // 6. Evidence URI Creation & File Naming
    // =========================================================================

    @Test
    fun testCreateEvidenceUriNamingPatternAndUniqueness() {
        val generatedFiles = mutableSetOf<File>()

        for (i in 1..20) {
            val (_, file) = evidenceManager.createEvidenceUri()

            // Verify naming conventions
            assertTrue("Filename must start with evidence_: ${file.name}", file.name.startsWith("evidence_"))
            assertTrue("Filename must end with .jpg: ${file.name}", file.name.endsWith(".jpg"))

            // Verify parent folder
            assertEquals("File parent must be the configured baseDir", tempDir.canonicalPath, file.parentFile?.canonicalPath)

            // Verify uniqueness
            assertFalse("Each generated evidence file must be unique", generatedFiles.contains(file))
            generatedFiles.add(file)
        }

        assertEquals("Should have generated 20 distinct evidence files", 20, generatedFiles.size)
    }

    @Test
    fun testBaseDirectoryAutoCreation() {
        val nonExistentNestedDir = File(tempDir, "nested/evidence/store")
        assertFalse(nonExistentNestedDir.exists())

        val managerWithNewDir = FakeEvidenceManager(nonExistentNestedDir)
        assertTrue("Base directory must be created automatically upon instantiation", nonExistentNestedDir.exists())

        val (_, file) = managerWithNewDir.createEvidenceUri()
        assertEquals(nonExistentNestedDir.canonicalPath, file.parentFile?.canonicalPath)
    }

    // =========================================================================
    // 7. Interface Polymorphism
    // =========================================================================

    @Test
    fun testPolymorphicEvidenceManagerContract() {
        val manager: EvidenceManager = evidenceManager

        val testBytes = "RescueMesh Offline Field Evidence".toByteArray(Charsets.UTF_8)
        val file = File(tempDir, "polymorphic.jpg").apply { writeBytes(testBytes) }

        val hash = manager.computeSha256(file)
        assertEquals(64, hash.length)

        val deleted = manager.cleanupFile(file)
        assertTrue(deleted)
    }
}
