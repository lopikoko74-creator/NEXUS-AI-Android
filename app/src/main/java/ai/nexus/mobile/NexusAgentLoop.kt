package ai.nexus.mobile

data class NexusExecutionOutcome(
    val success: Boolean,
    val observation: String,
    val taskCompleted: Boolean = false,
    val requiresConfirmation: Boolean = false
)

data class NexusPendingConfirmation internal constructor(
    val request: String,
    val plan: NexusAiPlan,
    internal val observations: List<String>,
    internal val completedSteps: Int
)

sealed interface NexusAgentResult {
    data class Completed(val summary: String, val steps: Int, val observations: List<String>) : NexusAgentResult
    data class AwaitingConfirmation(val pending: NexusPendingConfirmation, val explanation: String) : NexusAgentResult
    data class Unsupported(val reason: String, val observations: List<String>) : NexusAgentResult
    data class Failed(val reason: String, val observations: List<String>) : NexusAgentResult
}

/** Bounded observe-plan-act loop. Every next model turn receives the executor's observation. */
class NexusAgentLoop(
    private val generate: suspend (String) -> String,
    private val execute: suspend (NexusAiPlan, userConfirmed: Boolean) -> NexusExecutionOutcome,
    private val maxSteps: Int = 6
) {
    init { require(maxSteps in 1..10) }

    suspend fun run(request: String): NexusAgentResult {
        val clean = request.trim()
        if (clean.isEmpty()) return NexusAgentResult.Failed("Пустая команда", emptyList())
        return continueLoop(clean, emptyList(), 0, null, false)
    }

    suspend fun resume(pending: NexusPendingConfirmation): NexusAgentResult =
        continueLoop(pending.request, pending.observations, pending.completedSteps, pending.plan, true)

    private suspend fun continueLoop(
        request: String,
        initialObservations: List<String>,
        initialSteps: Int,
        firstPlan: NexusAiPlan?,
        firstPlanConfirmed: Boolean
    ): NexusAgentResult {
        val observations = initialObservations.toMutableList()
        val seen = mutableSetOf<String>()
        var steps = initialSteps
        var plan = firstPlan
        var confirmed = firstPlanConfirmed

        while (steps < maxSteps) {
            if (plan == null) {
                val raw = try {
                    generate(NexusAiPlanner.buildPrompt(request, observations))
                } catch (error: Exception) {
                    return NexusAgentResult.Failed(
                        "Локальная модель не смогла построить план: ${error.message ?: "ошибка генерации"}",
                        observations
                    )
                }
                plan = when (val parsed = NexusAiPlanner.parse(raw)) {
                    is NexusPlanParseResult.Valid -> parsed.plan
                    is NexusPlanParseResult.Invalid -> return NexusAgentResult.Failed(parsed.reason, observations)
                }
            }

            val activePlan = plan ?: return NexusAgentResult.Failed("Модель не вернула план", observations)
            when (activePlan) {
                is NexusAiPlan.Answer -> return NexusAgentResult.Completed(activePlan.text, steps, observations)
                is NexusAiPlan.Done -> return NexusAgentResult.Completed(activePlan.summary, steps, observations)
                is NexusAiPlan.Unsupported -> return NexusAgentResult.Unsupported(activePlan.reason, observations)
                else -> Unit
            }

            val signature = signature(activePlan)
            if (!seen.add(signature)) {
                return NexusAgentResult.Failed("Модель повторяет уже предложенное действие; цикл остановлен", observations)
            }

            val outcome = try {
                execute(activePlan, confirmed)
            } catch (error: Exception) {
                NexusExecutionOutcome(false, "Ошибка исполнителя: ${error.message ?: "неизвестная ошибка"}")
            }
            if (outcome.requiresConfirmation && !confirmed) {
                return NexusAgentResult.AwaitingConfirmation(
                    NexusPendingConfirmation(request, activePlan, observations.toList(), steps),
                    outcome.observation
                )
            }

            steps++
            observations += "Step $steps / ${activePlan.javaClass.simpleName}: ${outcome.observation.take(400)}"
            if (outcome.success && outcome.taskCompleted) {
                return NexusAgentResult.Completed(outcome.observation, steps, observations)
            }
            if (!outcome.success) observations += "Previous action failed; choose a different action or stop."
            plan = null
            confirmed = false
        }
        return NexusAgentResult.Failed("Достигнут предел автономных шагов ($maxSteps); задача не подтверждена", observations)
    }

    private fun signature(plan: NexusAiPlan): String = when (plan) {
        is NexusAiPlan.OpenApp -> "OPEN_APP:${plan.appName.lowercase()}"
        is NexusAiPlan.TapText -> "TAP_TEXT:${plan.exactLabel.lowercase()}"
        is NexusAiPlan.EnterText -> "ENTER_TEXT:${plan.text}"
        NexusAiPlan.GoBack -> "GO_BACK"
        is NexusAiPlan.Answer -> "ANSWER:${plan.text}"
        is NexusAiPlan.Done -> "DONE:${plan.summary}"
        is NexusAiPlan.Unsupported -> "UNSUPPORTED:${plan.reason}"
    }
}
