package com.rescuemesh.ai.evidence

import android.net.Uri
import java.io.File

/**
 * Interface contract for managing disaster evidence files (photos, recordings).
 *
 * Implemented by [PhotoEvidenceManager] for production Android environments
 * and [FakeEvidenceManager] for test / JVM environments.
 */
interface EvidenceManager {
    /**
     * Generates a new unique evidence file in app-private persistent storage
     * and creates a content [Uri] for camera capture.
     *
     * @return A pair of content [Uri] and target [File] on disk.
     */
    fun createEvidenceUri(): Pair<Uri, File>

    /**
     * Computes a cryptographic SHA-256 hex digest (64 lowercase hex characters)
     * of the given physical file using streaming I/O.
     *
     * @param file The evidence file to hash. Must exist, be a regular file, and not be empty.
     * @throws IllegalArgumentException if the file does not exist, is not a regular file, or is empty (0 bytes).
     * @return 64-character lowercase hexadecimal SHA-256 string.
     */
    fun computeSha256(file: File): String

    /**
     * Overload to compute SHA-256 of the given file, optionally allowing 0-byte empty files
     * (e.g. for NIST empty vector verification).
     *
     * @param file The evidence file to hash.
     * @param allowEmpty If true, allows hashing 0-byte files; otherwise throws [IllegalArgumentException].
     * @throws IllegalArgumentException if the file does not exist, is not a regular file, or if [allowEmpty] is false and file is empty.
     * @return 64-character lowercase hexadecimal SHA-256 string.
     */
    fun computeSha256(file: File, allowEmpty: Boolean): String = computeSha256(file)

    /**
     * Computes a cryptographic SHA-256 hex digest of the given in-memory byte array.
     *
     * @param bytes The raw byte array to hash.
     * @return 64-character lowercase hexadecimal SHA-256 string.
     */
    fun computeSha256(bytes: ByteArray): String

    /**
     * Deletes the specified evidence file from storage (e.g. on camera cancellation or corrupt file).
     *
     * @param file The file to delete.
     * @return `true` if the file existed and was successfully deleted, `false` otherwise.
     */
    fun cleanupFile(file: File): Boolean
}
