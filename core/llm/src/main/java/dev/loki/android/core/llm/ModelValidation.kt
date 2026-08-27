package dev.loki.android.core.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ModelDetection {
    data class Detected(
        val runtime: ModelRuntime,
        val format: ModelFormat,
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

class GgufModelDetector : ModelDetector {
    override fun detect(file: File): ModelDetection {
        if (!file.isFile || file.length() < GGUF_MAGIC.size) return ModelDetection.Unknown
        return try {
            RandomAccessFile(file, "r").use { input ->
                val magic = ByteArray(GGUF_MAGIC.size)
                input.readFully(magic)
                if (magic.contentEquals(GGUF_MAGIC)) {
                    ModelDetection.Detected(ModelRuntime.LLAMA_CPP, ModelFormat.GGUF)
                } else {
                    ModelDetection.Unknown
                }
            }
        } catch (_: Exception) {
            ModelDetection.Unknown
        }
    }

    companion object {
        private val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
    }
}

class GgufModelValidator(
    private val detector: ModelDetector = GgufModelDetector()
) : ModelValidator {
    override suspend fun validate(file: File): ValidationResult = withContext(Dispatchers.IO) {
        when (detector.detect(file)) {
            is ModelDetection.Detected -> ValidationResult.Valid(ModelRuntime.LLAMA_CPP, ModelFormat.GGUF)
            ModelDetection.Unknown -> ValidationResult.Invalid("File is not a recognized GGUF artifact")
        }
    }
}

class LiteRtModelValidator(private val context: Context) : ModelValidator {
    override suspend fun validate(file: File): ValidationResult = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext ValidationResult.Invalid("Model artifact does not exist")
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .build()
            LlmInference.createFromOptions(context, options).close()
            ValidationResult.Valid(ModelRuntime.LITERT_LM, ModelFormat.LITERT_MODEL)
        } catch (error: Exception) {
            ValidationResult.Invalid("LiteRT-LM model initialization failed: ${error.message}")
        }
    }
}

sealed interface ValidationResult {
    data class Valid(val runtime: ModelRuntime, val format: ModelFormat) : ValidationResult
    data class Invalid(val reason: String) : ValidationResult
}
