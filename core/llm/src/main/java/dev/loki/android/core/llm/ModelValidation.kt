package dev.loki.android.core.llm

import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ModelDetection {
    data class Detected(
        val runtime: ModelRuntime = ModelRuntime.LITERT_LM,
        val format: ModelFormat = ModelFormat.LITERT_MODEL,
        val family: String? = null,
        val confidence: MetadataConfidence = MetadataConfidence.VERIFIED
    ) : ModelDetection

    data object Unknown : ModelDetection
}

interface ModelDetector {
    fun detect(file: File): ModelDetection
}

interface ModelValidator {
    suspend fun validate(file: File): ValidationResult
}

class LiteRtModelDetector : ModelDetector {
    override fun detect(file: File): ModelDetection {
        if (file.isFile && file.extension.equals("litertlm", ignoreCase = true)) {
            return ModelDetection.Detected(ModelRuntime.LITERT_LM, ModelFormat.LITERT_MODEL)
        }
        return ModelDetection.Unknown
    }
}

class LiteRtModelValidator : ModelValidator {
    override suspend fun validate(file: File): ValidationResult = withContext(Dispatchers.IO) {
        if (!file.isFile || !file.canRead()) {
            return@withContext ValidationResult.Invalid("Model file does not exist or is not readable")
        }

        if (!file.extension.equals("litertlm", ignoreCase = true)) {
            return@withContext ValidationResult.Invalid("File must have a .litertlm extension")
        }

        try {
            val config = EngineConfig(modelPath = file.absolutePath)
            Engine(config).use { engine ->
                engine.initialize()
            }
            ValidationResult.Valid(ModelRuntime.LITERT_LM, ModelFormat.LITERT_MODEL)
        } catch (error: Exception) {
            ValidationResult.Invalid("LiteRT-LM validation failed: ${error.message}")
        }
    }
}

sealed interface ValidationResult {
    data class Valid(val runtime: ModelRuntime, val format: ModelFormat) : ValidationResult
    data class Invalid(val reason: String) : ValidationResult
}
