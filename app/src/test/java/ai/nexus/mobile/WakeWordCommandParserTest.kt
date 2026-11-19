package ai.nexus.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordCommandParserTest {
    @Test
    fun extractsCommandFromSameUtteranceAsWakeWord() {
        val result = WakeWordCommandParser.parse("NEXUS, открой YouTube", awaitingCommand = false)
        assertTrue(result.wakeWordDetected)
        assertEquals("открой YouTube", result.command)
    }

    @Test
    fun supportsRussianWakeWordAndPunctuation() {
        val result = WakeWordCommandParser.parse("Нексус — включи видео на YouTube GTA 6", awaitingCommand = false)
        assertTrue(result.wakeWordDetected)
        assertEquals("включи видео на YouTube GTA 6", result.command)
    }

    @Test
    fun preservesSeparateFollowUpCommand() {
        val result = WakeWordCommandParser.parse("найди обзор Poco F6", awaitingCommand = true)
        assertTrue(result.wakeWordDetected)
        assertEquals("найди обзор Poco F6", result.command)
    }

    @Test
    fun stripsRepeatedWakeWordFromFollowUp() {
        val result = WakeWordCommandParser.parse("NEXUS, открой карты", awaitingCommand = true)
        assertEquals("открой карты", result.command)
    }

    @Test
    fun distinguishesWakeOnlyFromUnrelatedSpeech() {
        val wakeOnly = WakeWordCommandParser.parse("Нексус", awaitingCommand = false)
        assertTrue(wakeOnly.wakeWordDetected)
        assertNull(wakeOnly.command)

        val unrelated = WakeWordCommandParser.parse("открой YouTube", awaitingCommand = false)
        assertFalse(unrelated.wakeWordDetected)
        assertNull(unrelated.command)
    }
}
