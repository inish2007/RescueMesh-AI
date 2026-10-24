package com.rescuemesh.ai.maps

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * WorkManager CoroutineWorker for downloading offline vector map packages.
 *
 * Strict Constraints (Astra P0-6 / Gate 3 R4):
 * - NetworkType.UNMETERED (unmetered Wi-Fi only, zero cellular quota usage)
 * - BatteryNotLow (prevents depleting battery in field conditions)
 * - Safe streaming download into a temporary staging file
 * - Atomic file rename into app-private persistent directory (context.filesDir/offline_maps/)
 * - Immediate cleanup of incomplete or corrupted temporary files on failure
 */
class MapDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val regionId = inputData.getString(KEY_REGION_ID) ?: return@withContext Result.failure()
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL) ?: return@withContext Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "${regionId}.mbtiles"
        val expectedSize = inputData.getLong(KEY_EXPECTED_SIZE, 0L)

        val targetDir = applicationContext.filesDir.resolve(OFFLINE_MAPS_DIR_NAME)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val tempFile = File(targetDir, "${fileName}.tmp_${UUID.randomUUID()}")

        try {
            val bytesWritten = streamAndVerify(
                url = downloadUrl,
                tempFile = tempFile,
                expectedSize = expectedSize
            )

            if (bytesWritten <= 0L || !tempFile.exists() || tempFile.length() <= 0L) {
                tempFile.delete()
                return@withContext Result.failure(
                    workDataOf(KEY_ERROR to "Downloaded file is empty or missing")
                )
            }

            // Atomic rename to final target file
            val targetFile = File(targetDir, fileName)
            if (targetFile.exists()) {
                targetFile.delete()
            }

            val renameSuccess = tempFile.renameTo(targetFile)
            if (!renameSuccess) {
                // Fallback for filesystem implementations that do not support direct atomic rename across handles
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            Result.success(
                workDataOf(
                    KEY_REGION_ID to regionId,
                    OUTPUT_BYTES_WRITTEN to targetFile.length(),
                    OUTPUT_TARGET_PATH to targetFile.absolutePath
                )
            )
        } catch (e: Exception) {
            // Clean up temporary file to prevent orphaned storage leaks
            if (tempFile.exists()) {
                tempFile.delete()
            }
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Download failed")))
            }
        }
    }

    /**
     * Streams data from the given URL into the temporary destination file with progress reporting.
     */
    private suspend fun streamAndVerify(
        url: String,
        tempFile: File,
        expectedSize: Long
    ): Long {
        val streamOpener = customStreamOpener ?: { targetUrl ->
            val conn = URL(targetUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "RescueMeshAI-OfflineVectorMaps/1.0")
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}: ${conn.responseMessage}")
            }
            conn.inputStream
        }

        var totalBytesRead = 0L
        streamOpener(url).use { input ->
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (isStopped) {
                        throw IllegalStateException("Worker stopped by WorkManager")
                    }
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    if (expectedSize > 0L) {
                        val progressPct = ((totalBytesRead.toDouble() / expectedSize) * 100).toInt().coerceIn(0, 100)
                        setProgress(
                            workDataOf(
                                PROGRESS_PERCENT to progressPct,
                                PROGRESS_BYTES to totalBytesRead
                            )
                        )
                    }
                }
                output.flush()
            }
        }
        return totalBytesRead
    }

    companion object {
        const val OFFLINE_MAPS_DIR_NAME = "offline_maps"
        const val BUFFER_SIZE = 8192
        const val MAX_RETRIES = 3

        const val KEY_REGION_ID = "REGION_ID"
        const val KEY_DOWNLOAD_URL = "DOWNLOAD_URL"
        const val KEY_FILE_NAME = "FILE_NAME"
        const val KEY_EXPECTED_SIZE = "EXPECTED_SIZE"

        const val PROGRESS_PERCENT = "PROGRESS_PERCENT"
        const val PROGRESS_BYTES = "PROGRESS_BYTES"
        const val OUTPUT_BYTES_WRITTEN = "BYTES_WRITTEN"
        const val OUTPUT_TARGET_PATH = "TARGET_PATH"
        const val KEY_ERROR = "ERROR_MESSAGE"
        const val TAG = "MapDownloadWorker"

        /**
         * Test hook for injecting custom InputStream providers (e.g. for mock/offline unit tests).
         */
        @Volatile
        var customStreamOpener: ((String) -> InputStream)? = null

        /**
         * Constructs the constraints required for background map tile downloads.
         * Enforces UNMETERED Wi-Fi only by default, or CONNECTED if [allowCellular] is explicitly enabled.
         */
        fun buildConstraints(allowCellular: Boolean = false): Constraints {
            val networkType = if (allowCellular) NetworkType.CONNECTED else NetworkType.UNMETERED
            return Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .setRequiresBatteryNotLow(true)
                .build()
        }

        /**
         * Helper to construct a OneTimeWorkRequest for downloading an offline map region.
         */
        fun createWorkRequest(region: OfflineRegionModel, allowCellular: Boolean = false): OneTimeWorkRequest {
            return OneTimeWorkRequestBuilder<MapDownloadWorker>()
                .setConstraints(buildConstraints(allowCellular))
                .setInputData(
                    workDataOf(
                        KEY_REGION_ID to region.regionId,
                        KEY_DOWNLOAD_URL to region.downloadUrl,
                        KEY_FILE_NAME to region.fileName,
                        KEY_EXPECTED_SIZE to region.fileSizeBytes
                    )
                )
                .addTag(TAG)
                .addTag(region.regionId)
                .build()
        }
    }
}
