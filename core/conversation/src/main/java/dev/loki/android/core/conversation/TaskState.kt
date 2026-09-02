package dev.loki.android.core.conversation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sealed interface representing application-owned task state for multi-turn tool flows.
 *
 * The advancing tool and resolved state are derived directly from the state's own fields
 * (D3 — no separate mapping registry).
 */
sealed interface TaskState {
    /** The tool that can resolve or advance the state right now, derived from state fields. */
    val advancingTool: String?

    /** Whether the state is resolved and no longer blocks capability switching. */
    val resolved: Boolean
}

/**
 * Represents a contact candidate exposed to the task state and model context.
 * Note: Phone numbers are stored for app-side resolution only and MUST NOT be
 * included in the model prompt context.
 */
@Serializable
data class ContactCandidate(
    val id: String = "",
    val name: String = "",
    @SerialName("number")
    val phoneNumber: String = ""
)

/**
 * Task state for disambiguating and confirming contact selection before placing a call.
 *
 * Derived invariant:
 * - candidates unresolved (selectedId == null) -> advancingTool = "select_contact"
 * - candidate selected but unconfirmed (!confirmed) -> advancingTool = "call_contact"
 * - confirmed -> advancingTool = null, resolved = true
 */
data class ContactResolution(
    val candidates: List<ContactCandidate>,
    val selectedId: String? = null,
    val confirmed: Boolean = false
) : TaskState {
    override val resolved: Boolean
        get() = confirmed

    override val advancingTool: String?
        get() = when {
            resolved -> null
            selectedId == null && candidates.isNotEmpty() -> "select_contact"
            selectedId != null && !confirmed -> "call_contact"
            else -> null
        }
}
