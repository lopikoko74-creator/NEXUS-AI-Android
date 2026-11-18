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
}
