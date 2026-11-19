package ai.nexus.mobile

/**
 * Parses the wake word and an optional command from one recognition result.
 * Supports both "NEXUS, open YouTube" and a separate follow-up command.
 */
object WakeWordCommandParser {
    data class Result(
        val wakeWordDetected: Boolean,
        val command: String?
    )

    private val wakeWord = Regex(
        "(?<![\\p{L}\\p{N}_])(?:nexus|нексус|некcус|некус)(?![\\p{L}\\p{N}_])",
        RegexOption.IGNORE_CASE
    )
    private val leadingWakeWord = Regex(
        "^\\s*(?:nexus|нексус|некcус|некус)(?:\\s*[,;:—-]\\s*|\\s+)?",
        RegexOption.IGNORE_CASE
    )

    fun parse(phrase: String, awaitingCommand: Boolean): Result {
        val clean = phrase.trim()
        if (clean.isEmpty()) return Result(false, null)

        if (awaitingCommand) {
            val command = leadingWakeWord.replaceFirst(clean, "").trim()
            return Result(true, command.takeIf { it.isNotBlank() })
        }

        val match = wakeWord.find(clean) ?: return Result(false, null)
        val command = clean.substring(match.range.last + 1)
            .trim()
            .trimStart(',', ';', ':', '—', '-')
            .trim()
        return Result(true, command.takeIf { it.isNotBlank() })
    }
}
