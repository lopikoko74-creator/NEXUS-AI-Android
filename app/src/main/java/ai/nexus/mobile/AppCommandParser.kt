package ai.nexus.mobile

/**
 * Parses a small, explicit set of app-opening commands.
 * This is an intent parser for already-recognized text, not speech recognition.
 */
object AppCommandParser {
    data class OpenApp(val appName: String)

    private val openCommand = Regex(
        "^\\s*(?:открой|открыть|запусти|запустить|перейди\\s+в|переключись\\s+на)\\s+(?:приложение\\s+)?(.+?)\\s*[.!?]*\\s*$",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String): OpenApp? {
        if (text.isBlank()) return null
        val normalized = VoiceAliasResolver.normalize(text)
        val match = openCommand.matchEntire(normalized) ?: return null
        val target = match.groupValues[1]
            .trim()
            .trimEnd('.', '!', '?', ',', ';', ':')
            .trim()
        if (target.isBlank() || target.length > 64) return null
        return OpenApp(target)
    }
}
