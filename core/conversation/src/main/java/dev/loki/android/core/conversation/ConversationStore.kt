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
import kotlinx.serialization.json.Json

/**
 * Durable storage of conversations and their turn history as JSON files in app-private storage.
 * Operations are executed on ioDispatcher with Mutex synchronization and atomic file renames.
 */
class ConversationStore(
    val baseDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : this(
        try {
            context.filesDir?.let { File(it, "conversations") }
                ?: File(System.getProperty("java.io.tmpdir") ?: ".", "loki_conversations")
        } catch (_: Throwable) {
            File(System.getProperty("java.io.tmpdir") ?: ".", "loki_conversations")
        },
        ioDispatcher
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

    private fun getConversationFile(id: String): File = File(baseDir, "$id.json")
    private fun getTempFile(id: String): File = File(baseDir, "$id.json.tmp")

    private fun loadLocked(id: String): ConversationRecord? {
        val file = getConversationFile(id)
        if (!file.exists()) return null
        return try {
            val content = file.readText()
            json.decodeFromString<ConversationRecord>(content)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load conversation: $id", e)
            null
        }
    }

    private fun saveLocked(record: ConversationRecord): Boolean {
        return try {
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }
            val targetFile = getConversationFile(record.id)
            val tempFile = getTempFile(record.id)

            val content = json.encodeToString(ConversationRecord.serializer(), record)
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
            Log.e(TAG, "Failed to save conversation: ${record.id}", e)
            false
        }
    }

    private fun createLocked(
        id: String = UUID.randomUUID().toString(),
        title: String = "New Chat"
    ): ConversationRecord {
        val now = System.currentTimeMillis()
        val record = ConversationRecord(
            id = id,
            title = title,
            createdAt = now,
            updatedAt = now,
            turns = emptyList()
        )
        saveLocked(record)
        return record
    }

    suspend fun createConversation(
        id: String = UUID.randomUUID().toString(),
        title: String = "New Chat"
    ): ConversationRecord = withContext(ioDispatcher) {
        mutex.withLock {
            createLocked(id, title)
        }
    }

    suspend fun listConversations(): List<ConversationRecord> = withContext(ioDispatcher) {
        mutex.withLock {
            val files = baseDir.listFiles { file -> file.isFile && file.extension == "json" } ?: emptyArray()
            val records = mutableListOf<ConversationRecord>()

            for (file in files) {
                try {
                    val content = file.readText()
                    val record = json.decodeFromString<ConversationRecord>(content)
                    records.add(record)
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to read conversation file: ${file.name}", e)
                }
            }

            records.sortedByDescending { it.updatedAt }
        }
    }

    suspend fun searchTurns(query: String, limit: Int = 5): List<TurnSearchResult> = withContext(ioDispatcher) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank() || limit <= 0) return@withContext emptyList()

        mutex.withLock {
            val files = baseDir.listFiles { file -> file.isFile && file.extension == "json" } ?: emptyArray()
            val results = mutableListOf<TurnSearchResult>()

            for (file in files) {
                try {
                    val content = file.readText()
                    val record = json.decodeFromString<ConversationRecord>(content)
                    for (turn in record.turns) {
                        val turnText = when (turn) {
                            is ConversationTurn.User -> turn.text
                            is ConversationTurn.Assistant -> turn.text
                            else -> null
                        }
                        if (turnText != null && turnText.contains(trimmedQuery, ignoreCase = true)) {
                            results.add(
                                TurnSearchResult(
                                    conversationId = record.id,
                                    conversationTitle = record.title,
                                    snippet = turnText.trim(),
                                    dateEpochMs = turn.timestamp
                                )
                            )
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to read conversation file during search: ${file.name}", e)
                }
            }

            results.sortedByDescending { it.dateEpochMs }.take(limit)
        }
    }

    suspend fun loadConversation(id: String): ConversationRecord? = withContext(ioDispatcher) {
        mutex.withLock {
            loadLocked(id)
        }
    }

    suspend fun saveConversation(record: ConversationRecord): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            saveLocked(record)
        }
    }

    suspend fun deleteConversation(id: String): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            try {
                val file = getConversationFile(id)
                val temp = getTempFile(id)
                if (temp.exists()) temp.delete()
                if (file.exists()) file.delete() else false
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to delete conversation: $id", e)
                false
            }
        }
    }

    suspend fun renameConversation(id: String, title: String): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            val existing = loadLocked(id) ?: return@withContext false
            val updated = existing.copy(
                title = title,
                updatedAt = System.currentTimeMillis()
            )
            saveLocked(updated)
        }
    }

    suspend fun appendTurn(
        conversationId: String,
        turn: ConversationTurn,
        autoTitle: Boolean = true
    ): ConversationRecord? = withContext(ioDispatcher) {
        mutex.withLock {
            val existing = loadLocked(conversationId) ?: createLocked(id = conversationId)
            val now = System.currentTimeMillis()

            val newTitle = if (autoTitle && (existing.title == "New Chat" || existing.title.isBlank()) && turn is ConversationTurn.User) {
                val clean = turn.text.trim().lines().firstOrNull() ?: ""
                if (clean.length > 40) clean.take(40) + "…" else clean.ifEmpty { existing.title }
            } else {
                existing.title
            }

            val updated = existing.copy(
                title = newTitle,
                updatedAt = now,
                turns = existing.turns + turn
            )
            val saved = saveLocked(updated)
            if (saved) updated else null
        }
    }

    companion object {
        private const val TAG = "ConversationStore"
    }
}
