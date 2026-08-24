package dev.loki.android.core.tools

import kotlinx.serialization.Serializable

enum class ToolErrorCode {
    PERMISSION_DENIED,
    NOT_FOUND,
    EXECUTION_ERROR,
    VALIDATION_ERROR,
    TIMEOUT
}

@Serializable
data class ToolResult(
    val success: Boolean,
    val data: Map<String, String>? = null,
    val error: String? = null,
    val errorCode: String? = null
) {
    companion object {
        fun success(data: Map<String, String> = emptyMap()) = ToolResult(
            success = true,
            data = data
        )

        fun error(message: String, code: ToolErrorCode = ToolErrorCode.EXECUTION_ERROR) = ToolResult(
            success = false,
            error = message,
            errorCode = code.name
        )
    }
}
