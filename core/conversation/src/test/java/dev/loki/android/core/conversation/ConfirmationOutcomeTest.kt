package dev.loki.android.core.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ConfirmationOutcome.from].
 *
 * Design contract being tested:
 *   - from() is a FORMAT DECODER, not a semantic interpreter.
 *   - The LLM is responsible for classifying the user's intent.
 *   - from() only converts the LLM's known output formats to the enum.
 *
 * Accepted formats:
 *   1. Bare exact token (case-insensitive after trim): CONFIRMED / DECLINED / UNKNOWN
 *   2. Known JSON label envelope: {"label": "CONFIRMED"} etc.
 *
 * Everything else → UNKNOWN (safe default).
 */
class ConfirmationOutcomeTest {

    // ── Step 1: Bare exact token ──────────────────────────────────────────────

    @Test
    fun `bare CONFIRMED maps to CONFIRMED`() {
        assertEquals(ConfirmationOutcome.CONFIRMED, ConfirmationOutcome.from("CONFIRMED"))
    }

    @Test
    fun `bare DECLINED maps to DECLINED`() {
        assertEquals(ConfirmationOutcome.DECLINED, ConfirmationOutcome.from("DECLINED"))
    }

    @Test
    fun `bare UNKNOWN maps to UNKNOWN`() {
        assertEquals(ConfirmationOutcome.UNKNOWN, ConfirmationOutcome.from("UNKNOWN"))
    }

    @Test
    fun `lowercase confirmed maps to CONFIRMED`() {
        assertEquals(ConfirmationOutcome.CONFIRMED, ConfirmationOutcome.from("confirmed"))
    }

    @Test
    fun `lowercase declined maps to DECLINED`() {
        assertEquals(ConfirmationOutcome.DECLINED, ConfirmationOutcome.from("declined"))
    }

    @Test
    fun `mixed case Confirmed maps to CONFIRMED`() {
        assertEquals(ConfirmationOutcome.CONFIRMED, ConfirmationOutcome.from("Confirmed"))
    }

    @Test
    fun `leading and trailing whitespace is trimmed before matching`() {
        assertEquals(ConfirmationOutcome.CONFIRMED, ConfirmationOutcome.from("  CONFIRMED  "))
        assertEquals(ConfirmationOutcome.DECLINED, ConfirmationOutcome.from("\nDECLINED\n"))
        assertEquals(ConfirmationOutcome.UNKNOWN, ConfirmationOutcome.from("\t UNKNOWN \t"))
    }

    @Test
    fun `empty string maps to UNKNOWN`() {
        assertEquals(ConfirmationOutcome.UNKNOWN, ConfirmationOutcome.from(""))
    }

    @Test
    fun `blank whitespace-only string maps to UNKNOWN`() {
        assertEquals(ConfirmationOutcome.UNKNOWN, ConfirmationOutcome.from("   "))
    }

    // ── Step 2: Known JSON label envelope ─────────────────────────────────────

    @Test
    fun `JSON label envelope CONFIRMED maps to CONFIRMED`() {
        assertEquals(ConfirmationOutcome.CONFIRMED, ConfirmationOutcome.from("""{"label": "CONFIRMED"}"""))
    }

    @Test
    fun `JSON label envelope DECLINED maps to DECLINED`() {
        assertEquals(ConfirmationOutcome.DECLINED, ConfirmationOutcome.from("""{"label": "DECLINED"}"""))
    }

    @Test
    fun `JSON label envelope UNKNOWN maps to UNKNOWN`() {
        assertEquals(ConfirmationOutcome.UNKNOWN, ConfirmationOutcome.from("""{"label": "UNKNOWN"}"""))
    }

    @Test
    fun `JSON label envelope without spaces maps to CONFIRMED`() {
        assertEquals(ConfirmationOutcome.CONFIRMED, ConfirmationOutcome.from("""{"label":"CONFIRMED"}"""))
    }

    @Test
    fun `JSON label envelope with lowercase value maps to CONFIRMED`() {
        assertEquals(ConfirmationOutcome.CONFIRMED, ConfirmationOutcome.from("""{"label": "confirmed"}"""))
    }

    @Test
    fun `JSON envelope with unknown label value maps to UNKNOWN`() {
        assertEquals(ConfirmationOutcome.UNKNOWN, ConfirmationOutcome.from("""{"label": "YES"}"""))
    }

    @Test
    fun `JSON with different key name does not match and maps to UNKNOWN`() {
        // "result" key is not the known envelope — should not be decoded
        assertEquals(ConfirmationOutcome.UNKNOWN, ConfirmationOutcome.from("""{"result": "CONFIRMED"}"""))
    }

    // ── Step 3: False positives that must NOT match ───────────────────────────

    @Test
    fun `prose containing CONFIRMED substring does NOT map to CONFIRMED`() {
        // Old substring-contains behaviour was a bug — this must be UNKNOWN
        assertEquals(ConfirmationOutcome.UNKNOWN, ConfirmationOutcome.from("I'm not CONFIRMED"))
    }

    @Test
    fun `prose containing DECLINED substring does NOT map to DECLINED`() {
        assertEquals(ConfirmationOutcome.UNKNOWN, ConfirmationOutcome.from("I never DECLINED anything"))
    }

    @Test
    fun `multi-token prose with both CONFIRMED and DECLINED does NOT match either`() {
        // Previously DECLINED would win due to priority; now both are UNKNOWN
        assertEquals(
            ConfirmationOutcome.UNKNOWN,
            ConfirmationOutcome.from("The previous answer was CONFIRMED, but I DECLINED")
        )
    }

    @Test
    fun `question text containing CONFIRMED does NOT map to CONFIRMED`() {
        // The model sometimes echoes back the confirmation question — must not be decoded
        assertEquals(
            ConfirmationOutcome.UNKNOWN,
            ConfirmationOutcome.from("Shall I call Mom? The user responded CONFIRMED.")
        )
    }

    @Test
    fun `free-form affirmation without exact token maps to UNKNOWN`() {
        // Semantic interpretation is the LLM's job, not from()'s
        assertEquals(ConfirmationOutcome.UNKNOWN, ConfirmationOutcome.from("yes please go ahead"))
        assertEquals(ConfirmationOutcome.UNKNOWN, ConfirmationOutcome.from("haan karo"))
        assertEquals(ConfirmationOutcome.UNKNOWN, ConfirmationOutcome.from("sure"))
    }

    @Test
    fun `free-form negation without exact token maps to UNKNOWN`() {
        assertEquals(ConfirmationOutcome.UNKNOWN, ConfirmationOutcome.from("no don't do that"))
        assertEquals(ConfirmationOutcome.UNKNOWN, ConfirmationOutcome.from("nahi"))
        assertEquals(ConfirmationOutcome.UNKNOWN, ConfirmationOutcome.from("cancel it"))
    }

    @Test
    fun `token with punctuation suffix does NOT match`() {
        // "CONFIRMED." is not the bare token — should be UNKNOWN
        assertEquals(ConfirmationOutcome.UNKNOWN, ConfirmationOutcome.from("CONFIRMED."))
        assertEquals(ConfirmationOutcome.UNKNOWN, ConfirmationOutcome.from("DECLINED!"))
    }

    @Test
    fun `JSON with CONFIRMED in prose value does NOT match`() {
        // The JSON must have "label" key with exactly one of the three tokens
        assertEquals(
            ConfirmationOutcome.UNKNOWN,
            ConfirmationOutcome.from("""{"message": "User said CONFIRMED"}""")
        )
    }
}
