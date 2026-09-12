package dev.loki.android.core.tools.local

import android.content.Context
import dev.loki.android.core.tools.LocalTool
import dev.loki.android.core.tools.ToolErrorCode
import dev.loki.android.core.tools.ToolParam
import dev.loki.android.core.tools.ToolParamType
import dev.loki.android.core.tools.ToolResult

/**
 * Internal advancing tool for contact disambiguation.
 * Visibility is governed by ContactResolution.advancingTool == "select_contact".
 */
class SelectContactTool : LocalTool {
    override val name: String = "select_contact"
    override val capability: String = "calling"
    override val isInternal: Boolean = true
    override val description: String = "Select a contact candidate from the disambiguation list by candidate ID."
    override val parameters: Map<String, ToolParam> = mapOf(
        "candidate_id" to ToolParam(
            type = ToolParamType.STRING,
            description = "The candidate ID (e.g. 'c1', 'c2') of the contact to select",
            required = true
        )
    )

    override suspend fun execute(context: Context, arguments: Map<String, Any?>): ToolResult {
        val candidateId = arguments["candidate_id"]?.toString()?.trim()
            ?: return ToolResult.error("Missing candidate_id", ToolErrorCode.VALIDATION_ERROR)

        return ToolResult.success(
            mapOf(
                "candidate_id" to candidateId,
                "status" to "selected"
            )
        )
    }
}
