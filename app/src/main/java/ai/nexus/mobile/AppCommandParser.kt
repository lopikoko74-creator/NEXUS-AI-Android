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

    /**
     * When installedApps is supplied, only return a direct-launch intent when the
     * complete spoken target matches one installed app label. Compound requests
     * such as "open MAX and find a chat" must be left to the agent.
     */
    fun parse(text: String, installedApps: List<String>? = null): OpenApp? {
        if (text.isBlank()) return null
        val normalized = VoiceAliasResolver.normalize(text)
        val match = openCommand.matchEntire(normalized) ?: return null
        val target = match.groupValues[1]
            .trim()
            .trimEnd('.', '!', '?', ',', ';', ':')
            .trim()
        if (target.isBlank() || target.length > 64) return null
        if (installedApps == null) return OpenApp(target)

        fun appKey(value: String): String = VoiceAliasResolver.normalize(value)
            .lowercase()
            .replace("макс", "max")
            .filter { it.isLetterOrDigit() }

        val installedLabel = installedApps.firstOrNull { appKey(it) == appKey(target) }
            ?: return null
        return OpenApp(installedLabel)
    }

    data class YouTubeSearch(val query: String)

    private val youtubeSearchCommands = listOf(
        Regex("^\\s*(?:включи|найди|поищи|покажи)\\s+(?:мне\\s+)?(?:видео|ролик)\\s+(?:на\\s+)?(?:youtube|ютуб|YouTube)\\s+(.+?)\\s*[.!?]*\\s*$", RegexOption.IGNORE_CASE),
        Regex("^\\s*(?:найди|поищи|включи|открой)\\s+(?:на\\s+)?(?:youtube|ютуб)\\s+(.+?)\\s*[.!?]*\\s*$", RegexOption.IGNORE_CASE)
    )

    fun parseYouTubeSearch(text: String): YouTubeSearch? {
        if (text.isBlank() || text.length > 240) return null
        val normalized = VoiceAliasResolver.normalize(text).trim()
        val match = youtubeSearchCommands.firstNotNullOfOrNull { it.matchEntire(normalized) } ?: return null
        val query = match.groupValues[1].trim().trimEnd('.', '!', '?', ',', ';', ':').trim()
        if (query.isBlank() || query.length > 160) return null
        return YouTubeSearch(query)
    }
}
