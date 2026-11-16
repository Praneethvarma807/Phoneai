package com.example.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.example.data.preferences.CommanderPreferences
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AuthorizedDocumentEntry(
    val uriString: String,
    val displayName: String,
    val mimeType: String = "*/*",
    val sizeBytes: Long = -1L,
    val isTree: Boolean = false,
    val persistedAtMillis: Long = System.currentTimeMillis()
)

data class SafDocumentMatch(
    val uriString: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sourceLabel: String
)

data class SafSearchResult(
    val matches: List<SafDocumentMatch>,
    val revokedOrMissing: List<String>,
    val totalAuthorizedRoots: Int
)

class DocumentAccessManager(
    private val context: Context,
    private val preferences: CommanderPreferences
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getAuthorizedEntries(): List<AuthorizedDocumentEntry> {
        val raw = preferences.authorizedDocumentsJson.first()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<AuthorizedDocumentEntry>>(raw)
        }.getOrDefault(emptyList())
    }

    suspend fun authorizePickedFile(uri: Uri): Result<AuthorizedDocumentEntry> = withContext(Dispatchers.IO) {
        runCatching {
            takePersistableReadPermission(uri)
            val meta = resolveOpenableMetadata(uri)
                ?: throw FileNotFoundException("Selected file is no longer accessible.")
            val entry = AuthorizedDocumentEntry(
                uriString = uri.toString(),
                displayName = meta.first,
                mimeType = appContext.contentResolver.getType(uri) ?: "application/octet-stream",
                sizeBytes = meta.second,
                isTree = false
            )
            saveEntry(entry)
            entry
        }
    }

    suspend fun authorizePickedFolder(treeUri: Uri): Result<AuthorizedDocumentEntry> = withContext(Dispatchers.IO) {
        runCatching {
            takePersistableReadPermission(treeUri)
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val name = resolveTreeDisplayName(docUri)
                ?: docId.substringAfterLast(':').ifBlank { "Selected Folder" }
            val entry = AuthorizedDocumentEntry(
                uriString = treeUri.toString(),
                displayName = name,
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                sizeBytes = -1L,
                isTree = true
            )
            saveEntry(entry)
            entry
        }
    }

    suspend fun revokeAccess(uriString: String) = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
        if (uri != null) {
            runCatching {
                appContext.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        val current = getAuthorizedEntries().filterNot { it.uriString == uriString }
        preferences.setAuthorizedDocumentsJson(json.encodeToString(current))
    }

    suspend fun searchAuthorizedDocuments(query: String, limit: Int = 20): SafSearchResult =
        withContext(Dispatchers.IO) {
            val entries = getAuthorizedEntries()
            val q = query.trim().lowercase()
            val matches = mutableListOf<SafDocumentMatch>()
            val revokedOrMissing = mutableListOf<String>()

            for (entry in entries) {
                if (matches.size >= limit) break
                val uri = runCatching { Uri.parse(entry.uriString) }.getOrNull() ?: continue
                if (!entry.isTree) {
                    val meta = resolveOpenableMetadata(uri)
                    if (meta == null) {
                        revokedOrMissing.add(entry.displayName)
                        continue
                    }
                    val name = meta.first
                    if (q.isBlank() || name.lowercase().contains(q)) {
                        matches.add(
                            SafDocumentMatch(
                                uriString = entry.uriString,
                                displayName = name,
                                mimeType = entry.mimeType,
                                sizeBytes = meta.second,
                                sourceLabel = "SAF File"
                            )
                        )
                    }
                } else {
                    val scanned = scanTreeChildren(uri, entry.displayName, q, limit - matches.size)
                    if (scanned == null) {
                        revokedOrMissing.add(entry.displayName)
                    } else {
                        matches.addAll(scanned)
                    }
                }
            }

            SafSearchResult(
                matches = matches,
                revokedOrMissing = revokedOrMissing,
                totalAuthorizedRoots = entries.size
            )
        }

    suspend fun readTextPreview(uri: Uri, maxBytes: Int = 12_288): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val mime = appContext.contentResolver.getType(uri).orEmpty().lowercase()
            val meta = resolveOpenableMetadata(uri)
                ?: throw FileNotFoundException("File not found or access permission was revoked.")
            val nameLower = meta.first.lowercase()
            val isReadableText = mime.startsWith("text/") ||
                mime == "application/json" ||
                mime == "application/xml" ||
                TEXT_EXTENSIONS.any { nameLower.endsWith(it) }

            if (!isReadableText) {
                throw UnsupportedOperationException(
                    "Unsupported inline preview type (${mime.ifBlank { "binary" }}). Use Open to launch in an external viewer."
                )
            }

            val bytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(maxBytes)
                val read = input.read(buffer)
                if (read <= 0) ByteArray(0) else buffer.copyOf(read)
            } ?: throw FileNotFoundException("Unable to open input stream for ${meta.first}.")

            String(bytes, Charsets.UTF_8).trim().ifBlank { "(Empty text document)" }
        }
    }

    private fun scanTreeChildren(
        treeUri: Uri,
        folderName: String,
        queryLower: String,
        remainingSlots: Int
    ): List<SafDocumentMatch>? {
        return try {
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )
            val found = mutableListOf<SafDocumentMatch>()
            appContext.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext() && found.size < remainingSlots) {
                    val docId = if (idIdx >= 0) cursor.getString(idIdx) else continue
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else continue
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else "*/*"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else -1L
                    if (queryLower.isBlank() || name.lowercase().contains(queryLower)) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        found.add(
                            SafDocumentMatch(
                                uriString = docUri.toString(),
                                displayName = name,
                                mimeType = mime,
                                sizeBytes = size,
                                sourceLabel = "Folder: $folderName"
                            )
                        )
                    }
                }
            }
            found
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveOpenableMetadata(uri: Uri): Pair<String, Long>? = try {
        var name: String? = null
        var size = -1L
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx)
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
            }
        }
        val finalName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: return null
        finalName to size
    } catch (_: SecurityException) {
        null
    } catch (_: FileNotFoundException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun resolveTreeDisplayName(docUri: Uri): String? = runCatching {
        appContext.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun takePersistableReadPermission(uri: Uri) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private suspend fun saveEntry(entry: AuthorizedDocumentEntry) {
        val current = getAuthorizedEntries().filterNot { it.uriString == entry.uriString }
        val updated = (listOf(entry) + current).take(MAX_PERSISTED_ENTRIES)
        preferences.setAuthorizedDocumentsJson(json.encodeToString(updated))
    }

    companion object {
        private const val MAX_PERSISTED_ENTRIES = 25
        private val TEXT_EXTENSIONS = listOf(".txt", ".md", ".csv", ".json", ".log", ".xml", ".yaml", ".yml")
    }
}
