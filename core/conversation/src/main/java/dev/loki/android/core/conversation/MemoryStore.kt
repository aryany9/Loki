package dev.loki.android.core.conversation

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

import dev.loki.android.core.models.ConversationMode

@Serializable
enum class MemorySource {
    MODEL_TOOL,
    USER_MANUAL
}

/**
 * Defines visibility scope for a memory entry across interaction modalities.
 * [GLOBAL] memories are visible in all modes.
 * [VOICE] memories are only visible to voice sessions.
 * [CHAT] memories are only visible to chat sessions.
 */
@Serializable
enum class MemoryScope {
    GLOBAL,
    VOICE,
    CHAT;

    fun isApplicableTo(mode: ConversationMode): Boolean = when (this) {
        GLOBAL -> true
        VOICE -> mode == ConversationMode.VOICE
        CHAT -> mode == ConversationMode.CHAT
    }
}

@Serializable
data class MemoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val source: MemorySource = MemorySource.MODEL_TOOL,
    /** Visibility scope; defaults to GLOBAL for backward compatibility with existing serialized entries. */
    val scope: MemoryScope = MemoryScope.GLOBAL
)

/**
 * Durable storage of user memory entries as a single JSON file under app-private storage.
 * Operations are executed on ioDispatcher with Mutex synchronization and atomic file renames.
 */
class MemoryStore(
    val baseDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        nowMillis: () -> Long = { System.currentTimeMillis() }
    ) : this(
        try {
            context.filesDir?.let { File(it, "memory") }
                ?: File(System.getProperty("java.io.tmpdir") ?: ".", "loki_memory")
        } catch (_: Throwable) {
            File(System.getProperty("java.io.tmpdir") ?: ".", "loki_memory")
        },
        ioDispatcher,
        nowMillis
    )

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
    }

    private fun getMemoryFile(): File = File(baseDir, "memories.json")
    private fun getTempFile(): File = File(baseDir, "memories.json.tmp")

    private fun loadLocked(): List<MemoryEntry> {
        val file = getMemoryFile()
        if (!file.exists()) return emptyList()
        return try {
            val content = file.readText()
            if (content.isBlank()) return emptyList()
            json.decodeFromString(ListSerializer(MemoryEntry.serializer()), content)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load memories, treating as empty", e)
            emptyList()
        }
    }

    private fun saveLocked(entries: List<MemoryEntry>): Boolean {
        return try {
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }
            val targetFile = getMemoryFile()
            val tempFile = getTempFile()

            val content = json.encodeToString(ListSerializer(MemoryEntry.serializer()), entries)
            FileOutputStream(tempFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }

            if (tempFile.renameTo(targetFile)) {
                true
            } else {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
                true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save memories", e)
            false
        }
    }

    suspend fun getAll(): List<MemoryEntry> = withContext(ioDispatcher) {
        mutex.withLock {
            loadLocked().sortedByDescending { it.updatedAtEpochMs }
        }
    }

    /**
     * Returns memories applicable to [mode], ordered most-recently-updated first,
     * clamped to [maxCount] entries and [maxChars] total characters.
     *
     * Visibility rules:
     * - [MemoryScope.GLOBAL] entries are included for all modes.
     * - [MemoryScope.VOICE] entries are included only when [mode] is [ConversationMode.VOICE].
     * - [MemoryScope.CHAT] entries are included only when [mode] is [ConversationMode.CHAT].
     */
    suspend fun getMemoriesFor(
        mode: ConversationMode,
        maxChars: Int,
        maxCount: Int = 10
    ): List<MemoryEntry> = withContext(ioDispatcher) {
        mutex.withLock {
            val sorted = loadLocked()
                .filter { it.scope.isApplicableTo(mode) }
                .sortedByDescending { it.updatedAtEpochMs }
            val result = mutableListOf<MemoryEntry>()
            var charCount = 0
            for (entry in sorted) {
                if (result.size >= maxCount) break
                val line = "- ${entry.text.trim()}"
                if (charCount + line.length + 1 > maxChars) break
                result.add(entry)
                charCount += line.length + 1
            }
            result
        }
    }

    suspend fun add(
        text: String,
        source: MemorySource = MemorySource.MODEL_TOOL,
        timestampEpochMs: Long = nowMillis()
    ): MemoryEntry = withContext(ioDispatcher) {
        mutex.withLock {
            val trimmed = text.trim()
            val existingList = loadLocked().toMutableList()

            val existingIndex = existingList.indexOfFirst { it.text.trim().equals(trimmed, ignoreCase = false) }
            if (existingIndex >= 0) {
                val existing = existingList[existingIndex]
                val updated = existing.copy(
                    updatedAtEpochMs = timestampEpochMs,
                    source = source
                )
                existingList[existingIndex] = updated
                saveLocked(existingList)
                updated
            } else {
                val newEntry = MemoryEntry(
                    id = UUID.randomUUID().toString(),
                    text = trimmed,
                    createdAtEpochMs = timestampEpochMs,
                    updatedAtEpochMs = timestampEpochMs,
                    source = source
                )
                existingList.add(newEntry)
                saveLocked(existingList)
                newEntry
            }
        }
    }

    suspend fun update(
        id: String,
        text: String,
        timestampEpochMs: Long = nowMillis()
    ): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            val existingList = loadLocked().toMutableList()
            val index = existingList.indexOfFirst { it.id == id }
            if (index < 0) return@withContext false

            val existing = existingList[index]
            val updated = existing.copy(
                text = text.trim(),
                updatedAtEpochMs = timestampEpochMs
            )
            existingList[index] = updated
            saveLocked(existingList)
        }
    }

    suspend fun delete(id: String): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            val existingList = loadLocked().toMutableList()
            val removed = existingList.removeAll { it.id == id }
            if (removed) {
                saveLocked(existingList)
                true
            } else {
                false
            }
        }
    }

    suspend fun clear(): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            saveLocked(emptyList())
        }
    }

    companion object {
        private const val TAG = "MemoryStore"
    }
}
