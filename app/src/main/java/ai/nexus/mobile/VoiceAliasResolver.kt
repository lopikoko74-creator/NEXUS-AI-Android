package ai.nexus.mobile

/**
 * Converts colloquial names and common spoken inflections into canonical entity names
 * before intent/entity matching. This is deterministic text normalization, not ASR.
 *
 * Keep patterns bounded to whole words so aliases do not rewrite parts of unrelated words.
 */
object VoiceAliasResolver {
    private data class Alias(val pattern: Regex, val canonical: String)

    private val aliases = listOf(
        Alias(Regex("(?<![\\p{L}\\p{N}_])телег(?:а|у|е|ой|и|рамм?)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE), "Telegram"),
        Alias(Regex("(?<![\\p{L}\\p{N}_])telegram(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE), "Telegram"),
        Alias(Regex("(?<![\\p{L}\\p{N}_])ватсап(?:а|у|е|ом)?(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE), "WhatsApp"),
        Alias(Regex("(?<![\\p{L}\\p{N}_])вотсап(?:а|у|е|ом)?(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE), "WhatsApp"),
        Alias(Regex("(?<![\\p{L}\\p{N}_])whatsapp(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE), "WhatsApp"),
        Alias(Regex("(?<![\\p{L}\\p{N}_])ютуб(?:а|у|е|ом)?(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE), "YouTube"),
        Alias(Regex("(?<![\\p{L}\\p{N}_])youtube(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE), "YouTube")
    )

    /** Returns the input with recognized aliases replaced by canonical names. */
    fun normalize(text: String): String {
        if (text.isBlank()) return text
        return aliases.fold(text) { current, alias ->
            alias.pattern.replace(current, alias.canonical)
        }
    }
}
