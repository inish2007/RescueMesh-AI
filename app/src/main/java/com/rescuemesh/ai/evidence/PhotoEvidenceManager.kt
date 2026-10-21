package com.rescuemesh.ai.evidence

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Production implementation of [EvidenceManager] storing disaster photos in
 * app-private storage (`context.filesDir/evidence/`) and issuing content URIs
 * via AndroidX [FileProvider].
 */
class PhotoEvidenceManager(private val context: Context) : EvidenceManager {

    val evidenceDir: File
        get() = File(context.filesDir, "evidence").apply {
            if (!exists()) {
                mkdirs()
            }
        }

    override fun createEvidenceUri(): Pair<Uri, File> {
        val dir = evidenceDir
        val filename = "evidence_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg"
        val file = File(dir, filename)
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        return Pair(uri, file)
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
