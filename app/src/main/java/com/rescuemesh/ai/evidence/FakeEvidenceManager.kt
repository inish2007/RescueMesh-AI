package com.rescuemesh.ai.evidence

import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Pure JVM test double implementation of [EvidenceManager].
 * Does not depend on Android runtime stubs or device FileProvider.
 */
class FakeEvidenceManager(
    val baseDir: File = File(System.getProperty("java.io.tmpdir"), "rescuemesh_evidence_fake")
) : EvidenceManager {

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
    }

    override fun createEvidenceUri(): Pair<Uri, File> {
        val filename = "evidence_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg"
        val file = File(baseDir, filename)
        val uri = Uri.parse("content://com.rescuemesh.ai.fileprovider/evidence/${file.name}")
        return Pair(uri, file)
    }

    fun createEvidenceFile(prefix: String = "evidence_"): File {
        return File.createTempFile(prefix, ".jpg", baseDir)
    }

    override fun computeSha256(file: File): String {
        return computeSha256(file, allowEmpty = false)
    }

    override fun computeSha256(file: File, allowEmpty: Boolean): String {
        require(file.exists()) { "Evidence file does not exist: ${file.absolutePath}" }
        require(file.isFile) { "Evidence path is not a file: ${file.absolutePath}" }
        if (!allowEmpty) {
            require(file.length() > 0L) { "Evidence file is empty (0 bytes): ${file.absolutePath}" }
        }

        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun cleanupFile(file: File): Boolean {
        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }
}
