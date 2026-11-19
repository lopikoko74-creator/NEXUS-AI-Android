package ai.nexus.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppCommandParserTest {
    @Test
    fun parsesRussianOpenCommandsAndAliases() {
        assertEquals("Telegram", AppCommandParser.parse("Открой телегу")?.appName)
        assertEquals("WhatsApp", AppCommandParser.parse("Запусти ватсап")?.appName)
        assertEquals("YouTube", AppCommandParser.parse("Перейди в ютуб!")?.appName)
    }

    @Test
    fun supportsOptionalAppWordAndInfinitive() {
        assertEquals("Telegram", AppCommandParser.parse("Открыть приложение Telegram")?.appName)
        assertEquals("YouTube", AppCommandParser.parse("Запустить YouTube")?.appName)
    }

    @Test
    fun rejectsUnrecognizedOrUnsafeFreeFormCommands() {
        assertNull(AppCommandParser.parse("Напиши сообщение в телегу"))
        assertNull(AppCommandParser.parse("удали приложение"))
        assertNull(AppCommandParser.parse(""))
        assertNull(AppCommandParser.parse("открой"))
    }

    @Test
    fun rejectsExcessivelyLongTarget() {
        assertNull(AppCommandParser.parse("открой " + "а".repeat(65)))
    }

    @Test
    fun directLaunchRequiresAnExactInstalledAppMatchWhenInventoryIsProvided() {
        assertEquals("MAX", AppCommandParser.parse("Открой приложение Макс", listOf("MAX", "Telegram"))?.appName)
        assertNull(AppCommandParser.parse(
            "Открой приложение Макс и найди чат Ананасик",
            listOf("MAX", "Telegram")
        ))
        assertNull(AppCommandParser.parse("Открой неизвестное приложение", listOf("MAX", "Telegram")))
    }

    @Test
    fun parsesNaturalYouTubeSearchCommands() {
        assertEquals("josping GTA 6", AppCommandParser.parseYouTubeSearch("Включи видео на YouTube josping GTA 6")?.query)
        assertEquals("обзор Poco F6", AppCommandParser.parseYouTubeSearch("Найди на ютубе обзор Poco F6")?.query)
        assertNull(AppCommandParser.parseYouTubeSearch("Включи видео"))
        assertNull(AppCommandParser.parseYouTubeSearch("удали видео на YouTube"))
    }
}
