package dev.loki.android.core.ui

data class LanguageOption(
    val tag: String,
    val displayName: String
)

val CONVERSATION_LANGUAGES: List<LanguageOption> = listOf(
    LanguageOption("auto", "Auto (Match user)"),
    LanguageOption("en", "English"),
    LanguageOption("hi", "Hindi (हिन्दी)"),
    LanguageOption("es", "Spanish (Español)"),
    LanguageOption("fr", "French (Français)"),
    LanguageOption("de", "German (Deutsch)"),
    LanguageOption("pt", "Portuguese (Português)"),
    LanguageOption("it", "Italian (Italiano)"),
    LanguageOption("zh", "Chinese (中文)"),
    LanguageOption("ja", "Japanese (日本語)"),
    LanguageOption("ko", "Korean (한국어)"),
    LanguageOption("ar", "Arabic (العربية)"),
    LanguageOption("ru", "Russian (Русский)")
)
