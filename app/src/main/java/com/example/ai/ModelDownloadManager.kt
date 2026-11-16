package com.example.ai

import android.content.Context
import android.net.Uri
import com.example.data.local.DownloadedModelDao
import com.example.data.local.DownloadedModelEntity
import com.example.domain.model.LocalModelSpec
import com.example.domain.model.ModelInstallStatus
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class DownloadProgressState(
    val modelId: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val status: ModelInstallStatus,
    val error: String? = null
) {
    val fraction: Float
        get() = if (totalBytes > 0L) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

class ModelDownloadManager(
    private val context: Context,
    private val dao: DownloadedModelDao,
    private val compatibilityManager: DeviceCompatibilityManager,
    private val client: OkHttpClient = defaultClient()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _progress = MutableStateFlow<Map<String, DownloadProgressState>>(emptyMap())
    val progress: StateFlow<Map<String, DownloadProgressState>> = _progress.asStateFlow()

    val downloadedModels: Flow<List<DownloadedModelEntity>> = dao.observeAll()

    fun modelsDir(): File = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    fun startOrResumeDownload(spec: LocalModelSpec) {
        if (activeJobs[spec.id]?.isActive == true) return

        val compat = compatibilityManager.checkModelCompatibility(spec.minRamMb, spec.minStorageMb)
        if (!compat.isCompatible) {
            _progress.update {
                it + (spec.id to DownloadProgressState(
                    modelId = spec.id,
                    bytesDownloaded = 0L,
                    totalBytes = spec.expectedSizeBytes,
                    status = ModelInstallStatus.FAILED,
                    error = compat.reason
                ))
            }
            return
        }

        val job = scope.launch {
            val partFile = File(modelsDir(), "${spec.fileName}.part")
            val checksumSidecar = File(modelsDir(), "${spec.fileName}.sha256")
            val finalFile = File(modelsDir(), spec.fileName)
            try {
                if (finalFile.exists() && finalFile.length() >= MIN_MODEL_BYTES) {
                    val savedExpectedSha = spec.expectedSha256 ?: checksumSidecar.takeIf { it.exists() }?.readText()?.trim()
                    if (validateDownloadedFile(finalFile, spec, savedExpectedSha)) {
                        markReady(spec, finalFile)
                        return@launch
                    } else {
                        finalFile.delete()
                    }
                }

                var expectedRemoteSize = -1L
                var serverSha256: String? = spec.expectedSha256
                    ?: checksumSidecar.takeIf { it.exists() }?.readText()?.trim()?.takeIf { isValidSha256Hex(it) }

                var attempt = 0
                var lastError: Exception? = null
                while (attempt < MAX_RETRIES) {
                    try {
                        val outcome = downloadOnce(spec, partFile, finalFile)
                        if (outcome.expectedTotalBytes > 0L) {
                            expectedRemoteSize = outcome.expectedTotalBytes
                        }
                        if (!outcome.serverSha256.isNullOrBlank()) {
                            serverSha256 = outcome.serverSha256
                            checksumSidecar.writeText(outcome.serverSha256)
                        }
                        lastError = null
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RangeMismatchException) {
                        partFile.delete()
                        checksumSidecar.delete()
                        attempt++
                    } catch (e: IOException) {
                        lastError = e
                        attempt++
                        if (attempt < MAX_RETRIES) {
                            val bytes = if (partFile.exists()) partFile.length() else 0L
                            updateProgress(
                                spec.id,
                                bytes,
                                spec.expectedSizeBytes,
                                ModelInstallStatus.DOWNLOADING,
                                "Connection interrupted; retrying ($attempt/$MAX_RETRIES)…"
                            )
                            delay(1500L * attempt)
                        }
                    }
                }
                if (lastError != null) throw lastError

                if (expectedRemoteSize > 0L && partFile.length() != expectedRemoteSize) {
                    partFile.delete()
                    throw IllegalStateException(
                        "Incomplete download (${partFile.length()} of $expectedRemoteSize bytes). Retry to resume."
                    )
                }

                if (!validateDownloadedFile(partFile, spec, serverSha256)) {
                    partFile.delete()
                    throw IllegalStateException("Downloaded model failed integrity or checksum verification.")
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                checksumSidecar.delete()
                markReady(spec, finalFile)
            } catch (_: CancellationException) {
                val bytes = if (partFile.exists()) partFile.length() else 0L
                updateProgress(spec.id, bytes, spec.expectedSizeBytes, ModelInstallStatus.PAUSED)
                dao.upsert(
                    DownloadedModelEntity(
                        modelId = spec.id,
                        displayName = spec.displayName,
                        fileName = spec.fileName,
                        localPath = finalFile.absolutePath,
                        expectedSizeBytes = spec.expectedSizeBytes,
                        downloadedBytes = bytes,
                        status = ModelInstallStatus.PAUSED.name
                    )
                )
            } catch (e: Exception) {
                val bytes = if (partFile.exists()) partFile.length() else 0L
                val msg = e.message ?: "Download failed"
                updateProgress(spec.id, bytes, spec.expectedSizeBytes, ModelInstallStatus.FAILED, msg)
                dao.upsert(
                    DownloadedModelEntity(
                        modelId = spec.id,
                        displayName = spec.displayName,
                        fileName = spec.fileName,
                        localPath = finalFile.absolutePath,
                        expectedSizeBytes = spec.expectedSizeBytes,
                        downloadedBytes = bytes,
                        status = ModelInstallStatus.FAILED.name
                    )
                )
            } finally {
                activeJobs.remove(spec.id)
            }
        }
        activeJobs[spec.id] = job
    }

    private suspend fun downloadOnce(
        spec: LocalModelSpec,
        partFile: File,
        finalFile: File
    ): DownloadOutcome {
        val existingBytes = if (partFile.exists()) partFile.length() else 0L
        val requestBuilder = Request.Builder().url(spec.downloadUrl)
        if (existingBytes > 0L) {
            requestBuilder.addHeader("Range", "bytes=$existingBytes-")
        }

        updateProgress(spec.id, existingBytes, spec.expectedSizeBytes, ModelInstallStatus.DOWNLOADING)
        dao.upsert(
            DownloadedModelEntity(
                modelId = spec.id,
                displayName = spec.displayName,
                fileName = spec.fileName,
                localPath = finalFile.absolutePath,
                expectedSizeBytes = spec.expectedSizeBytes,
                downloadedBytes = existingBytes,
                status = ModelInstallStatus.DOWNLOADING.name
            )
        )

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code == 416) {
                throw RangeMismatchException("Server rejected partial range offset $existingBytes")
            }
            if (!response.isSuccessful && response.code != 206) {
                val reason = when (response.code) {
                    401, 403 -> "HTTP ${response.code} (Access restricted or license confirmation required). Download the .litertlm file manually and use Import Local Model."
                    404 -> "HTTP 404 (Model file URL not found on host)."
                    429 -> "HTTP 429 (Rate limited by host). Please retry in a few moments."
                    else -> "HTTP ${response.code}: ${response.message}."
                }
                throw IllegalStateException(reason)
            }

            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if (contentType.contains("text/html") || contentType.contains("application/json")) {
                throw IllegalStateException("Host returned $contentType instead of a binary .litertlm model. Use Import Local Model.")
            }

            val body = response.body ?: throw IllegalStateException("Empty response body")
            val append = response.code == 206 && existingBytes > 0L
            val startOffset = if (append) existingBytes else 0L
            val contentLen = body.contentLength()
            val expectedTotal = resolveExpectedTotalBytes(response, startOffset, contentLen)
            val progressDenominator = if (expectedTotal > 0L) expectedTotal else spec.expectedSizeBytes
            val serverSha256 = extractSha256Header(response)

            body.byteStream().use { input ->
                FileOutputStream(partFile, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = startOffset
                    var lastEmit = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 250L) {
                            lastEmit = now
                            updateProgress(spec.id, downloaded, progressDenominator, ModelInstallStatus.DOWNLOADING)
                        }
                    }
                    output.flush()
                }
            }
            return DownloadOutcome(expectedTotalBytes = expectedTotal, serverSha256 = serverSha256)
        }
    }

    fun pauseDownload(modelId: String) {
        activeJobs[modelId]?.cancel()
    }

    suspend fun importLocalModel(uri: Uri, displayName: String? = null): Result<DownloadedModelEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val fileName = "custom_${System.currentTimeMillis()}.litertlm"
                val dest = File(modelsDir(), fileName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                } ?: throw IllegalStateException("Cannot open selected model file.")

                if (dest.length() < MIN_MODEL_BYTES) {
                    dest.delete()
                    throw IllegalStateException("Selected file is too small to be a valid .litertlm model.")
                }

                val sha = computeSha256(dest)
                val id = "local-${sha.take(10)}"
                val entity = DownloadedModelEntity(
                    modelId = id,
                    displayName = displayName ?: "Imported Local Model",
                    fileName = fileName,
                    localPath = dest.absolutePath,
                    expectedSizeBytes = dest.length(),
                    downloadedBytes = dest.length(),
                    sha256Checksum = sha,
                    status = ModelInstallStatus.READY.name,
                    isActive = false
                )
                dao.upsert(entity)
                entity
            }
        }

    suspend fun validateInstalledModel(modelId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = dao.getById(modelId) ?: throw IllegalStateException("Model not found")
            val file = File(entity.localPath)
            if (!file.exists() || file.name.endsWith(".part") || file.length() < MIN_MODEL_BYTES) {
                dao.upsert(entity.copy(status = ModelInstallStatus.CORRUPTED.name))
                throw IllegalStateException("Model file missing, partial, or truncated.")
            }
            val sha = computeSha256(file)
            if (!entity.sha256Checksum.isNullOrBlank() && !entity.sha256Checksum.equals(sha, ignoreCase = true)) {
                dao.upsert(entity.copy(status = ModelInstallStatus.CORRUPTED.name))
                throw IllegalStateException("Checksum mismatch.")
            }
            dao.upsert(entity.copy(sha256Checksum = sha, status = ModelInstallStatus.READY.name))
            "Verified (${file.length() / (1024 * 1024)} MB, SHA-256: ${sha.take(12)}…)"
        }
    }

    suspend fun deleteModel(modelId: String) = withContext(Dispatchers.IO) {
        pauseDownload(modelId)
        val entity = dao.getById(modelId)
        if (entity != null) {
            File(entity.localPath).delete()
            File(modelsDir(), "${entity.fileName}.part").delete()
            File(modelsDir(), "${entity.fileName}.sha256").delete()
        }
        dao.deleteById(modelId)
        _progress.update { it - modelId }
    }

    suspend fun getTotalStorageUsedBytes(): Long = withContext(Dispatchers.IO) {
        modelsDir().listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun shutdown() {
        activeJobs.values.forEach { it.cancel() }
        scope.cancel()
    }

    private suspend fun markReady(spec: LocalModelSpec, file: File) {
        val sha = computeSha256(file)
        dao.upsert(
            DownloadedModelEntity(
                modelId = spec.id,
                displayName = spec.displayName,
                fileName = spec.fileName,
                localPath = file.absolutePath,
                expectedSizeBytes = file.length(),
                downloadedBytes = file.length(),
                sha256Checksum = sha,
                status = ModelInstallStatus.READY.name
            )
        )
        updateProgress(spec.id, file.length(), file.length(), ModelInstallStatus.READY)
    }

    internal fun validateDownloadedFile(
        file: File,
        spec: LocalModelSpec,
        expectedSha256: String? = spec.expectedSha256
    ): Boolean {
        if (!file.exists() || file.length() < MIN_MODEL_BYTES) return false
        val shaTarget = expectedSha256?.takeIf { isValidSha256Hex(it) }
        if (shaTarget != null) {
            val actual = computeSha256(file)
            if (!actual.equals(shaTarget, ignoreCase = true)) return false
        }
        return true
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun updateProgress(
        modelId: String,
        downloaded: Long,
        total: Long,
        status: ModelInstallStatus,
        error: String? = null
    ) {
        _progress.update {
            it + (modelId to DownloadProgressState(modelId, downloaded, total, status, error))
        }
    }

    private data class DownloadOutcome(
        val expectedTotalBytes: Long,
        val serverSha256: String?
    )

    private class RangeMismatchException(message: String) : IOException(message)

    companion object {
        private const val MAX_RETRIES = 3
        internal const val MIN_MODEL_BYTES = 1_000_000L

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        internal fun isValidSha256Hex(value: String): Boolean {
            val clean = value.trim().removePrefix("\"").removeSuffix("\"")
            return clean.length == 64 && clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }

        internal fun extractSha256Header(response: Response): String? {
            val candidates = listOfNotNull(
                response.header("X-Linked-ETag"),
                response.header("ETag")
            )
            for (raw in candidates) {
                val clean = raw.trim().removePrefix("W/").removeSurrounding("\"")
                if (isValidSha256Hex(clean)) return clean.lowercase()
            }
            return null
        }

        internal fun resolveExpectedTotalBytes(
            response: Response,
            startOffset: Long,
            contentLength: Long
        ): Long {
            val contentRange = response.header("Content-Range")
            if (!contentRange.isNullOrBlank()) {
                val slashIdx = contentRange.lastIndexOf('/')
                if (slashIdx != -1) {
                    val total = contentRange.substring(slashIdx + 1).trim().toLongOrNull()
                    if (total != null && total > 0L) return total
                }
            }
            return if (contentLength > 0L) startOffset + contentLength else -1L
        }
    }
}
