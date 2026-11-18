package ai.nexus.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceAliasResolverTest {
    @Test
    fun telegramColloquialismsAndInflectionsResolveToCanonicalName() {
        assertEquals("Открой Telegram", VoiceAliasResolver.normalize("Открой телегу"))
        assertEquals("Напиши в Telegram", VoiceAliasResolver.normalize("Напиши в телеге"))
        assertEquals("Telegram уже установлен", VoiceAliasResolver.normalize("Телеграмм уже установлен"))
        assertEquals("Telegram", VoiceAliasResolver.normalize("телега"))
    }

    @Test
    fun commonAppNicknamesResolveToCanonicalNames() {
        assertEquals("Открой WhatsApp", VoiceAliasResolver.normalize("Открой ватсап"))
        assertEquals("Запусти YouTube", VoiceAliasResolver.normalize("Запусти ютуб"))
    }

    @Test
    fun aliasesDoNotRewritePartsOfOtherWords() {
        assertEquals("телеграмма", VoiceAliasResolver.normalize("телеграмма"))
        assertEquals("подтелега", VoiceAliasResolver.normalize("подтелега"))
        assertEquals("Telegram!", VoiceAliasResolver.normalize("телега!"))
    }

    @Test
    fun blankInputIsPreserved() {
        assertEquals("   ", VoiceAliasResolver.normalize("   "))
    }
}
