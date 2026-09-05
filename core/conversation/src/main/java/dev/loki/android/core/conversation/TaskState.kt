package dev.loki.android.core.conversation

import dev.loki.android.core.tools.TaskStateGate
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
 *
 * Implements [TaskStateGate] so that [ToolRegistry] can restrict the grammar at each stage:
 * - CONTACT_DISAMBIGUATION: only select_contact + general tools are exposed.
 * - CALL_CONFIRMATION: call_contact is hidden from grammar until confirmed.
 */
data class ContactResolution(
    val candidates: List<ContactCandidate>,
    val selectedId: String? = null,
    val isAsked: Boolean = false,
    val confirmed: Boolean = false
) : TaskState, TaskStateGate {
    override val resolved: Boolean
        get() = confirmed

    override val advancingTool: String?
        get() = when {
            resolved -> null
            selectedId == null && candidates.isNotEmpty() -> "select_contact"
            selectedId != null && !confirmed -> "call_contact"
            else -> null
        }

    /**
     * During CONTACT_DISAMBIGUATION (selectedId == null) restrict grammar to only select_contact.
     * Null otherwise (no restriction).
     */
    override val restrictToTool: String?
        get() = if (selectedId == null && candidates.isNotEmpty() && !confirmed) "select_contact" else null

    /**
     * Single hidden tool for legacy consumers:
     * - CONTACT_DISAMBIGUATION: ask_user
     * - Before confirmation question is asked (!isAsked): call_contact
     * - Awaiting confirmation answer (isAsked && !confirmed): ask_user
     */
    override val hiddenTool: String?
        get() = when {
            selectedId == null && candidates.isNotEmpty() && !confirmed -> "ask_user"
            selectedId != null && !isAsked && !confirmed -> "call_contact"
            selectedId != null && isAsked && !confirmed -> "ask_user"
            else -> null
        }

    /**
     * State-scoped grammar tool exclusion:
     * - CONTACT_DISAMBIGUATION (selectedId == null): exclude ask_user and call_contact.
     * - CALL_CONFIRMATION before question asked (!isAsked): exclude call_contact.
     * - AWAITING_CONFIRMATION (isAsked && !confirmed): exclude ask_user (prevents confirmation loops).
     * - CONFIRMED: no exclusions.
     */
    override val hiddenTools: Set<String>
        get() = when {
            selectedId == null && candidates.isNotEmpty() && !confirmed -> setOf("ask_user", "call_contact")
            selectedId != null && !isAsked && !confirmed -> setOf("call_contact")
            selectedId != null && isAsked && !confirmed -> setOf("ask_user")
            else -> emptySet()
        }
}
