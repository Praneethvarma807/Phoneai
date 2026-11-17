package com.example.memory

import com.example.data.local.PersonalMemoryDao
import com.example.data.local.PersonalMemoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class MemoryCategory(val label: String) {
    PREFERENCE("Preference"),
    WORKFLOW("Workflow"),
    LANGUAGE("Language"),
    GENERAL("Personal Note")
}

data class DecryptedMemoryItem(
    val id: Long,
    val memoryKey: String,
    val category: MemoryCategory,
    val summaryLabel: String,
    val valueText: String,
    val isHardwareEncrypted: Boolean,
    val updatedAt: Long
)

class PersonalMemoryRepository(
    private val dao: PersonalMemoryDao,
    private val cipher: MemoryCipher = MemorySecurityHelper.defaultCipher()
) {
    val memories: Flow<List<DecryptedMemoryItem>> = dao.observeAll().map { entities ->
        entities.mapNotNull { entity -> entity.toDecryptedOrNull() }
    }

    suspend fun saveMemory(
        rawKey: String,
        value: String,
        category: MemoryCategory = MemoryCategory.GENERAL,
        summaryLabel: String = rawKey
    ): Result<DecryptedMemoryItem> {
        val cleanKey = normalizeKey(rawKey)
        val cleanValue = value.trim()
        val cleanLabel = summaryLabel.trim().ifBlank { cleanKey.replace('_', ' ') }

        if (cleanKey.isBlank() || cleanValue.isBlank()) {
            return Result.failure(IllegalArgumentException("Memory key and value cannot be empty."))
        }
        if (MemorySecurityHelper.containsForbiddenSecret(cleanKey) ||
            MemorySecurityHelper.containsForbiddenSecret(cleanValue) ||
            MemorySecurityHelper.containsForbiddenSecret(cleanLabel)
        ) {
            return Result.failure(
                SecurityException("Blocked: OTPs, passwords, PINs, card numbers, and banking credentials cannot be saved to AI Memory.")
            )
        }

        val encrypted = cipher.encrypt(cleanValue)
        val now = System.currentTimeMillis()
        val existing = dao.getByKey(cleanKey)
        val entity = PersonalMemoryEntity(
            id = existing?.id ?: 0L,
            memoryKey = cleanKey,
            category = category.name,
            summaryLabel = cleanLabel.take(64),
            encryptedValue = encrypted.cipherText,
            isEncrypted = encrypted.isHardwareBacked,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        val rowId = dao.upsert(entity)
        val persistedId = if (entity.id != 0L) entity.id else rowId
        return Result.success(
            DecryptedMemoryItem(
                id = persistedId,
                memoryKey = cleanKey,
                category = category,
                summaryLabel = entity.summaryLabel,
                valueText = cleanValue,
                isHardwareEncrypted = encrypted.isHardwareBacked,
                updatedAt = now
            )
        )
    }

    suspend fun recallRelevant(query: String, limit: Int = 5): List<DecryptedMemoryItem> {
        val all = dao.getAllOnce().mapNotNull { it.toDecryptedOrNull() }
        if (all.isEmpty()) return emptyList()
        val terms = query.lowercase()
            .split(Regex("\\W+"))
            .filter { it.length >= 3 }
            .toSet()
        if (terms.isEmpty()) return all.take(limit)

        val scored = all.map { item ->
            val hay = "${item.memoryKey} ${item.summaryLabel} ${item.valueText} ${item.category.name}".lowercase()
            val hits = terms.count { hay.contains(it) }
            item to hits
        }
        val matched = scored.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
        return if (matched.isNotEmpty()) matched.take(limit) else all.take(limit.coerceAtMost(2))
    }

    suspend fun getAllDecrypted(): List<DecryptedMemoryItem> =
        dao.getAllOnce().mapNotNull { it.toDecryptedOrNull() }

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun deleteByQuery(query: String): Int {
        val needle = query.trim().lowercase()
        if (needle.isBlank()) return 0
        val all = getAllDecrypted()
        val targets = all.filter {
            it.memoryKey.contains(needle) ||
                it.summaryLabel.lowercase().contains(needle) ||
                it.valueText.lowercase().contains(needle)
        }
        targets.forEach { dao.deleteById(it.id) }
        return targets.size
    }

    suspend fun clearAll() = dao.clearAll()

    private fun PersonalMemoryEntity.toDecryptedOrNull(): DecryptedMemoryItem? {
        val plain = cipher.decrypt(encryptedValue) ?: return null
        val cat = runCatching { MemoryCategory.valueOf(category) }.getOrDefault(MemoryCategory.GENERAL)
        return DecryptedMemoryItem(
            id = id,
            memoryKey = memoryKey,
            category = cat,
            summaryLabel = summaryLabel,
            valueText = plain,
            isHardwareEncrypted = isEncrypted,
            updatedAt = updatedAt
        )
    }

    companion object {
        fun normalizeKey(raw: String): String =
            raw.trim()
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .ifBlank { "memory_${System.currentTimeMillis()}" }
                .take(48)
    }
}
