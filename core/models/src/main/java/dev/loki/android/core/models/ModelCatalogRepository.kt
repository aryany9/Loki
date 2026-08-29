package dev.loki.android.core.models

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class ModelCatalogRepository(
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun load(remoteUrl: String?, bundled: ModelCatalog): ModelCatalog = withContext(Dispatchers.IO) {
        if (remoteUrl == null) return@withContext bundled
        try {
            val connection = URL(remoteUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.requestMethod = "GET"
            connection.inputStream.use { input ->
                val remote = json.decodeFromString<ModelCatalog>(input.bufferedReader().readText())
                require(remote.schemaVersion == bundled.schemaVersion) { "Unsupported catalog schema" }
                remote
            }
        } catch (_: Exception) {
            bundled
        }
    }
}
