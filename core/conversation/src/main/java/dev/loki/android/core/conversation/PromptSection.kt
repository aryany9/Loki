package dev.loki.android.core.conversation

/**
 * Defines the ordered sections of a modality-scoped system prompt.
 *
 * Precedence tiers (lower ordinal = higher priority, assembled first):
 *
 * Tier 1 (Non-overridable): SYSTEM_FOUNDATION
 * Tier 2: MODALITY_PROFILE, DOMAIN_DIRECTIVES
 * Tier 3 (User config — subordinate): USER_CUSTOM_INSTRUCTION, SCOPED_MEMORIES
 * Tier 1 Recency Anchor (pinned last): TOOL_PROTOCOL, TURN_PROTOCOL
 */
enum class PromptSection {
    /** Core identity, privacy, safety — highest priority, never overridden. */
    SYSTEM_FOUNDATION,
    /** Modality-specific formatting profile (spoken vs Markdown). */
    MODALITY_PROFILE,
    /** Domain and capability-level directives. */
    DOMAIN_DIRECTIVES,
    /** User's custom instructions — framed as subordinate preferences. */
    USER_CUSTOM_INSTRUCTION,
    /** Scoped memories applicable to the current mode. */
    SCOPED_MEMORIES,
    /** JSON syntax for tool invocation — pinned at end for recency anchoring. */
    TOOL_PROTOCOL,
    /** Turn-yielding semantics (ask_user for VOICE, direct text for CHAT) — pinned at end. */
    TURN_PROTOCOL,
}
