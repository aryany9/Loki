package dev.loki.android.core.voice.stt

import android.content.Context
import android.util.Log

/**
 * Whisper multilingual BPE tokenizer loaded from the bundled
 * `assets/whisper/multilingual.tiktoken` asset.
 *
 * The tiktoken file is a base64-encoded list of (token, rank) pairs, one per line,
 * in the format: `<base64-token> <rank>`. Special tokens are appended after the BPE
 * vocabulary in a fixed order matching OpenAI's Whisper multilingual vocab (51,865 total).
 */
class WhisperTokenizer(context: Context) {

    // id → bytes (for decode)
    private val idToToken: Array<ByteArray>
    // Special token IDs
    val endOfTextToken: Int
    private val startOfTranscriptToken: Int
    private val languageTokenEn: Int
    private val transcribeToken: Int
    private val noTimestampsToken: Int

    init {
        val bpeTokens = mutableListOf<Pair<ByteArray, Int>>()

        context.assets.open("whisper/multilingual.tiktoken").bufferedReader().use { reader ->
            reader.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    val parts = trimmed.split(" ")
                    if (parts.size == 2) {
                        try {
                            val tokenBytes = android.util.Base64.decode(parts[0], android.util.Base64.DEFAULT)
                            val rank = parts[1].toInt()
                            bpeTokens.add(Pair(tokenBytes, rank))
                        } catch (e: Exception) {
                            val rank = parts[1].toIntOrNull()
                            if (rank != null) {
                                bpeTokens.add(Pair(parts[0].toByteArray(Charsets.UTF_8), rank))
                            }
                        }
                    }
                }
            }
        }

        // Sort by rank to get id -> token mapping
        bpeTokens.sortBy { it.second }

        val vocabSize = bpeTokens.size // 50257
        idToToken = Array(TOTAL_VOCAB_SIZE) { ByteArray(0) }
        for ((tokenBytes, rank) in bpeTokens) {
            if (rank < TOTAL_VOCAB_SIZE) idToToken[rank] = tokenBytes
        }

        // Special tokens for Whisper multilingual model (n_vocab = 50257):
        // 50257: <|endoftext|>
        // 50258: <|startoftranscript|>
        // 50259: <|en|> (English, index 0 of 99 languages)
        // 50358: <|translate|>
        // 50359: <|transcribe|>
        // 50360: <|startoflm|>
        // 50361: <|startofprev|>
        // 50362: <|nospeech|>
        // 50363: <|notimestamps|>
        endOfTextToken = vocabSize               // 50257
        startOfTranscriptToken = vocabSize + 1   // 50258
        languageTokenEn = vocabSize + 2          // 50259
        transcribeToken = vocabSize + 2 + 99 + 1 // 50359
        noTimestampsToken = vocabSize + 2 + 99 + 5 // 50363

        Log.i(TAG, "WhisperTokenizer loaded: $vocabSize BPE tokens, sot=$startOfTranscriptToken, en=$languageTokenEn, transcribe=$transcribeToken, notimestamps=$noTimestampsToken, eot=$endOfTextToken")
    }

    /**
     * Resolves the language token for a given BCP-47 tag in Whisper's multilingual vocabulary.
     */
    fun getLanguageToken(language: String): Int {
        val index = LANGUAGES.indexOf(language.lowercase(java.util.Locale.ROOT).substringBefore('-'))
        return if (index >= 0) {
            startOfTranscriptToken + 1 + index
        } else {
            languageTokenEn
        }
    }

    /**
     * Returns the prefix token IDs for transcription without timestamps:
     * When [language] is explicit: `[<|startoftranscript|>, <|lang|>, <|transcribe|>, <|notimestamps|>]`
     * When [language] is "auto" (or blank): `[<|startoftranscript|>, <|transcribe|>, <|notimestamps|>]`
     */
    fun getPrefixTokens(language: String = "auto"): IntArray {
        return if (language.isBlank() || language.equals("auto", ignoreCase = true)) {
            intArrayOf(
                startOfTranscriptToken,
                transcribeToken,
                noTimestampsToken
            )
        } else {
            intArrayOf(
                startOfTranscriptToken,
                getLanguageToken(language),
                transcribeToken,
                noTimestampsToken
            )
        }
    }

    /**
     * Decodes a list of token IDs to a UTF-8 string. Byte-level tokens (0–255) are assembled
     * first before UTF-8 decoding, skipping any special tokens (id >= BPE vocab size).
     */
    fun decode(tokenIds: List<Int>): String {
        val bytes = mutableListOf<Byte>()
        for (id in tokenIds) {
            if (id < idToToken.size && id >= 0) {
                val tokenBytes = idToToken[id]
                bytes.addAll(tokenBytes.toList())
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "WhisperTokenizer"
        private const val TOTAL_VOCAB_SIZE = 51865
        val LANGUAGES = listOf(
            "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl", "ar", "sv", "it", "id", "hi", "fi", "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no", "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk", "te", "fa", "lv", "bn", "sr", "az", "sl", "kn", "et", "mk", "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw", "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc", "ka", "be", "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo", "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl", "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su"
        )
    }
}
