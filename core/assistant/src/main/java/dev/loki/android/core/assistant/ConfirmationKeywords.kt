package dev.loki.android.core.assistant

/**
 * Keyword matcher for verbal confirmation verdicts on the voice assistant overlay.
 * Matches affirmative ("yes", "yeah", "sure", "confirm", "ok", "do it") and
 * negative ("no", "cancel", "stop", "don't") responses.
 */
object ConfirmationKeywords {

    val AFFIRMATIVE_KEYWORDS: Set<String> = setOf(
        "yes",
        "yeah",
        "sure",
        "confirm",
        "ok",
        "okay",
        "do it"
    )

    val NEGATIVE_KEYWORDS: Set<String> = setOf(
        "no",
        "cancel",
        "stop",
        "don't",
        "dont"
    )

    enum class Verdict {
        ACCEPTED,
        DENIED,
        UNRECOGNIZED
    }

    /**
     * Parses the transcript into an affirmative, negative, or unrecognized verdict.
     */
    fun parseVerdict(transcript: String): Verdict {
        val normalized = transcript.trim().lowercase()
            .replace(Regex("[^a-z0-9\\s']"), " ")
        val tokens = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }

        // Check negative keywords and phrases first (e.g. "don't do it", "no", "cancel")
        if (tokens.any { it in NEGATIVE_KEYWORDS } || normalized.contains("don't") || normalized.contains("dont")) {
            return Verdict.DENIED
        }

        // Multi-word phrase matches and affirmative keywords
        if (normalized.contains("do it") || tokens.any { it in AFFIRMATIVE_KEYWORDS }) {
            return Verdict.ACCEPTED
        }

        return Verdict.UNRECOGNIZED
    }
}
