package com.example.ai

import android.content.Context
import android.net.Uri
import com.example.data.local.DownloadedModelDao
import com.example.data.local.DownloadedModelEntity
import com.example.domain.model.LocalModelSpec
import com.example.domain.model.ModelInstallStatus
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

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
    private val compatibilityManager: DeviceCompatibilityManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

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
            val finalFile = File(modelsDir(), spec.fileName)
            try {
                if (finalFile.exists() && finalFile.length() > 10_000_000L) {
                    markReady(spec, finalFile)
                    return@launch
                }

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
                    if (!response.isSuccessful && response.code != 206) {
                        throw IllegalStateException("HTTP ${response.code}: ${response.message}. Public download unavailable.")
                    }
                    val body = response.body ?: throw IllegalStateException("Empty response body")
                    val append = response.code == 206 && existingBytes > 0L
                    val startOffset = if (append) existingBytes else 0L
                    val contentLen = body.contentLength()
                    val totalSize = when {
                        contentLen > 0L -> startOffset + contentLen
                        else -> spec.expectedSizeBytes
                    }

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
                                    updateProgress(spec.id, downloaded, totalSize, ModelInstallStatus.DOWNLOADING)
                                }
                            }
                            output.flush()
                        }
                    }

                    if (!validateDownloadedFile(partFile, spec)) {
                        partFile.delete()
                        throw IllegalStateException("Downloaded file failed validation check.")
                    }
                    if (finalFile.exists()) finalFile.delete()
                    if (!partFile.renameTo(finalFile)) {
                        partFile.copyTo(finalFile, overwrite = true)
                        partFile.delete()
                    }
                    markReady(spec, finalFile)
                }
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

                if (dest.length() < 1_000_000L) {
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
            if (!file.exists() || file.length() < 1_000_000L) {
                dao.upsert(entity.copy(status = ModelInstallStatus.CORRUPTED.name))
                throw IllegalStateException("Model file missing or truncated.")
            }
            val sha = computeSha256(file)
            if (!entity.sha256Checksum.isNullOrBlank() && entity.sha256Checksum != sha) {
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
        }
        dao.deleteById(modelId)
        _progress.update { it - modelId }
    }

    fun getTotalStorageUsedBytes(): Long {
        return modelsDir().listFiles()?.sumOf { it.length() } ?: 0L
    }

    private suspend fun markReady(spec: LocalModelSpec, file: File) {
        val sha = computeSha256(file)
        dao.upsert(
            DownloadedModelEntity(
                modelId = spec.id,
                displayName = spec.displayName,
                fileName = spec.fileName,
                localPath = file.absolutePath,
                expectedSizeBytes = spec.expectedSizeBytes,
                downloadedBytes = file.length(),
                sha256Checksum = sha,
                status = ModelInstallStatus.READY.name
            )
        )
        updateProgress(spec.id, file.length(), file.length(), ModelInstallStatus.READY)
    }

    private fun validateDownloadedFile(file: File, spec: LocalModelSpec): Boolean {
        if (!file.exists() || file.length() < 1_000_000L) return false
        if (!spec.expectedSha256.isNullOrBlank()) {
            val actual = computeSha256(file)
            if (!actual.equals(spec.expectedSha256, ignoreCase = true)) return false
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
}
