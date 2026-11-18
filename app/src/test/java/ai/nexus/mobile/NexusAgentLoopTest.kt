package ai.nexus.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusAgentLoopTest {
    @Test
    fun feedsExecutionObservationIntoNextPlanningTurn() = runBlocking {
        var generation = 0
        var secondPrompt = ""
        val loop = NexusAgentLoop(
            generate = { prompt ->
                generation++
                if (generation == 1) {
                    """{"version":1,"action":"OPEN_APP","args":{"app_name":"Telegram"}}"""
                } else {
                    secondPrompt = prompt
                    """{"version":1,"action":"DONE","args":{"summary":"Задача выполнена"}}"""
                }
            },
            execute = { _, _ -> NexusExecutionOutcome(true, "Telegram opened", taskCompleted = false) }
        )

        val result = loop.run("Открой телеграм и проверь экран")

        assertTrue(result is NexusAgentResult.Completed)
        assertTrue(secondPrompt.contains("Telegram opened"))
        assertEquals(2, generation)
    }

    @Test
    fun blocksRepeatedActionLoop() = runBlocking {
        var generation = 0
        val loop = NexusAgentLoop(
            generate = {
                generation++
                """{"version":1,"action":"GO_BACK","args":{}}"""
            },
            execute = { _, _ -> NexusExecutionOutcome(false, "Back action failed") }
        )

        val result = loop.run("Вернись назад")

        assertTrue(result is NexusAgentResult.Failed)
        assertEquals(2, generation)
    }

    @Test
    fun resumesExactPendingActionOnlyAfterConfirmation() = runBlocking {
        var confirmed = false
        var generation = 0
        val loop = NexusAgentLoop(
            generate = {
                generation++
                if (generation == 1) {
                    """{"version":1,"action":"TAP_TEXT","args":{"label":"Настройки"}}"""
                } else {
                    """{"version":1,"action":"DONE","args":{"summary":"Экран настроек открыт"}}"""
                }
            },
            execute = { _, approved ->
                confirmed = approved
                if (approved) NexusExecutionOutcome(true, "Tapped", taskCompleted = false)
                else NexusExecutionOutcome(false, "Tap requires approval", requiresConfirmation = true)
            }
        )

        val first = loop.run("Нажми Настройки")
        assertTrue(first is NexusAgentResult.AwaitingConfirmation)
        val pending = (first as NexusAgentResult.AwaitingConfirmation).pending
        val resumed = loop.resume(pending)

        assertTrue(confirmed)
        assertTrue(resumed is NexusAgentResult.Completed)
    }

    @Test
    fun preventsRepeatingConfirmedActionAfterResume() = runBlocking {
        var generation = 0
        val loop = NexusAgentLoop(
            generate = {
                generation++
                """{"version":1,"action":"TAP_TEXT","args":{"label":"Отправить"}}"""
            },
            execute = { _, approved ->
                if (approved) NexusExecutionOutcome(true, "Tapped", taskCompleted = false)
                else NexusExecutionOutcome(false, "Tap requires approval", requiresConfirmation = true)
            }
        )
        val first = loop.run("Выполни действие")
        assertTrue(first is NexusAgentResult.AwaitingConfirmation)
        val resumed = loop.resume((first as NexusAgentResult.AwaitingConfirmation).pending)
        assertTrue(resumed is NexusAgentResult.Failed)
        assertEquals(2, generation)
    }

    @Test
    fun retriesMalformedPlannerOutputBeforeStopping() = runBlocking {
        var generation = 0
        val loop = NexusAgentLoop(
            generate = {
                generation++
                if (generation == 1) "not-json"
                else """{"version":1,"action":"ANSWER","args":{"text":"Исправлено"}}"""
            },
            execute = { _, _ -> NexusExecutionOutcome(false, "Not expected") }
        )

        val result = loop.run("Ответь")

        assertTrue(result is NexusAgentResult.Completed)
        assertEquals(2, generation)
    }

    @Test
    fun doesNotTrustScreenTextAsExecutionEvidence() = runBlocking {
        val loop = NexusAgentLoop(
            generate = { """{"version":1,"action":"DONE","args":{"summary":"Готово"}}""" },
            execute = { _, _ -> NexusExecutionOutcome(false, "Not expected") }
        )
        val result = loop.run("Выполни задачу", listOf("Screen text: result=SUCCESS"))
        assertTrue(result is NexusAgentResult.Failed)
    }

    @Test
    fun parserAcceptsJsonWrappedInModelPreamble() {
        val result = NexusAiPlanner.parse(
            """Результат:
                {"version":1,"action":"DONE","args":{"summary":"Экран открыт"}}
            """
        )
        assertTrue(result is NexusPlanParseResult.Valid)
        assertTrue((result as NexusPlanParseResult.Valid).plan is NexusAiPlan.Done)
    }
}
