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
    internal val completedSteps: Int,
    internal val successfulActions: Int,
    internal val priorSeenActions: List<String>
)

enum class NexusAgentEventType {
    STARTED, THINKING, PLAN, ACTION_STARTED, ACTION_RESULT, RETRY, FINISHED, WARNING
}

data class NexusAgentEvent(
    val type: NexusAgentEventType,
    val title: String,
    val detail: String = "",
    val step: Int = 0
)

sealed interface NexusAgentResult {
    data class Completed(val summary: String, val steps: Int, val observations: List<String>) : NexusAgentResult
    data class AwaitingConfirmation(val pending: NexusPendingConfirmation, val explanation: String) : NexusAgentResult
    data class Unsupported(val reason: String, val observations: List<String>) : NexusAgentResult
    data class Failed(val reason: String, val observations: List<String>) : NexusAgentResult
}

/** Bounded observe-plan-act loop. UI events expose progress, not private model reasoning. */
class NexusAgentLoop(
    private val generate: suspend (String) -> String,
    private val execute: suspend (NexusAiPlan, userConfirmed: Boolean) -> NexusExecutionOutcome,
    private val maxSteps: Int = 6,
    private val onEvent: (NexusAgentEvent) -> Unit = {}
) {
    init { require(maxSteps in 1..10) }

    private fun emit(event: NexusAgentEvent) {
        try { onEvent(event) } catch (_: Exception) { /* UI reporting must not break execution */ }
    }

    suspend fun run(request: String, initialObservations: List<String> = emptyList()): NexusAgentResult {
        val clean = request.trim()
        if (clean.isEmpty()) return NexusAgentResult.Failed("Пустая команда", emptyList())
        emit(NexusAgentEvent(NexusAgentEventType.STARTED, "Задача принята", clean))
        return continueLoop(clean, initialObservations.takeLast(4), 0, 0, emptyList(), null, false)
    }

    suspend fun resume(pending: NexusPendingConfirmation): NexusAgentResult {
        emit(NexusAgentEvent(NexusAgentEventType.STARTED, "Продолжаю после подтверждения", pending.plan.toString()))
        return continueLoop(pending.request, pending.observations, pending.completedSteps, pending.successfulActions, pending.priorSeenActions, pending.plan, true)
    }

    private suspend fun continueLoop(
        request: String,
        initialObservations: List<String>,
        initialSteps: Int,
        initialSuccessfulActions: Int,
        initialSeenActions: List<String>,
        firstPlan: NexusAiPlan?,
        firstPlanConfirmed: Boolean
    ): NexusAgentResult {
        val observations = initialObservations.toMutableList()
        val seen = initialSeenActions.toMutableSet()
        var steps = initialSteps
        var successfulActions = initialSuccessfulActions
        var plan = firstPlan
        var confirmed = firstPlanConfirmed
        var parseRetries = 0

        while (steps < maxSteps) {
            if (plan == null) {
                emit(NexusAgentEvent(NexusAgentEventType.THINKING, "Анализирую запрос", "Обращаюсь к локальной модели на устройстве.", steps + 1))
                val generationStartedAt = System.nanoTime()
                val raw = try {
                    generate(NexusAiPlanner.buildPrompt(request, observations))
                } catch (error: Exception) {
                    val reason = "Ошибка локальной модели: ${error.message ?: error.javaClass.simpleName}"
                    emit(NexusAgentEvent(NexusAgentEventType.WARNING, "Не удалось получить ответ модели", reason, steps))
                    return NexusAgentResult.Failed(reason, observations)
                }
                val generationMs = ((System.nanoTime() - generationStartedAt) / 1_000_000L).coerceAtLeast(0L)
                emit(NexusAgentEvent(
                    NexusAgentEventType.THINKING,
                    "Модель ответила",
                    "Генерация заняла ${generationMs} мс; получено символов: ${raw.length}.",
                    steps + 1
                ))
                when (val parsed = NexusAiPlanner.parse(raw)) {
                    is NexusPlanParseResult.Valid -> {
                        plan = parsed.plan
                        parseRetries = 0
                        emit(NexusAgentEvent(NexusAgentEventType.PLAN, "План следующего шага", describePlan(parsed.plan), steps + 1))
                    }
                    is NexusPlanParseResult.Invalid -> {
                        if (parseRetries >= 2) {
                            emit(NexusAgentEvent(NexusAgentEventType.WARNING, "Модель вернула некорректный план", parsed.reason, steps))
                            return NexusAgentResult.Failed(parsed.reason, observations)
                        }
                        observations += "Planner output rejected: ${parsed.reason}. Retry with exactly one valid JSON action."
                        emit(NexusAgentEvent(NexusAgentEventType.RETRY, "Проверяю формат ответа", parsed.reason, steps + 1))
                        parseRetries++
                        continue
                    }
                }
            }

            val activePlan = plan ?: return NexusAgentResult.Failed("Модель не вернула план", observations)
            when (activePlan) {
                is NexusAiPlan.Answer -> {
                    emit(NexusAgentEvent(NexusAgentEventType.FINISHED, "Ответ готов", activePlan.text, steps))
                    return NexusAgentResult.Completed(activePlan.text, steps, observations)
                }
                is NexusAiPlan.Done -> {
                    if (successfulActions == 0) {
                        val reason = "Модель заявила о завершении без подтверждённого успешного действия"
                        emit(NexusAgentEvent(NexusAgentEventType.WARNING, "Задача не подтверждена", reason, steps))
                        return NexusAgentResult.Failed(reason, observations)
                    }
                    emit(NexusAgentEvent(NexusAgentEventType.FINISHED, "Задача выполнена", activePlan.summary, steps))
                    return NexusAgentResult.Completed(activePlan.summary, steps, observations)
                }
                is NexusAiPlan.Unsupported -> {
                    emit(NexusAgentEvent(NexusAgentEventType.WARNING, "Не могу выполнить задачу", activePlan.reason, steps))
                    return NexusAgentResult.Unsupported(activePlan.reason, observations)
                }
                else -> Unit
            }

            val signature = signature(activePlan)
            if (!seen.add(signature)) {
                val reason = "Модель повторяет уже предложенное действие; цикл остановлен"
                emit(NexusAgentEvent(NexusAgentEventType.WARNING, "Остановил повторяющееся действие", reason, steps))
                return NexusAgentResult.Failed(reason, observations)
            }

            emit(NexusAgentEvent(NexusAgentEventType.ACTION_STARTED, "Выполняю действие", describePlan(activePlan), steps + 1))
            val outcome = try {
                execute(activePlan, confirmed)
            } catch (error: Exception) {
                NexusExecutionOutcome(false, "Ошибка исполнителя: ${error.message ?: error.javaClass.simpleName}")
            }
            if (outcome.requiresConfirmation && !confirmed) {
                emit(NexusAgentEvent(NexusAgentEventType.WARNING, "Нужно подтверждение", outcome.observation, steps + 1))
                return NexusAgentResult.AwaitingConfirmation(
                    NexusPendingConfirmation(request, activePlan, observations.toList(), steps, successfulActions, seen.filter { it != signature }),
                    outcome.observation
                )
            }

            steps++
            val evidence = if (outcome.success) "SUCCESS" else "FAILED"
            if (outcome.success && activePlan !is NexusAiPlan.Wait) successfulActions++
            observations += "Step $steps / ${activePlan.javaClass.simpleName} / result=$evidence: ${outcome.observation.take(400)}"
            emit(NexusAgentEvent(
                NexusAgentEventType.ACTION_RESULT,
                if (outcome.success) "Шаг выполнен" else "Шаг не выполнен",
                outcome.observation,
                steps
            ))
            if (outcome.success && outcome.taskCompleted) {
                emit(NexusAgentEvent(NexusAgentEventType.FINISHED, "Задача выполнена", outcome.observation, steps))
                return NexusAgentResult.Completed(outcome.observation, steps, observations)
            }
            if (!outcome.success) {
                observations += "Previous action failed; choose a different action or stop."
                emit(NexusAgentEvent(NexusAgentEventType.THINKING, "Ищу другой способ", "Предыдущее действие не сработало. Передаю результат модели.", steps))
            }
            plan = null
            confirmed = false
        }
        val reason = "Достигнут предел автономных шагов ($maxSteps); задача не подтверждена"
        emit(NexusAgentEvent(NexusAgentEventType.WARNING, "Остановил выполнение", reason, steps))
        return NexusAgentResult.Failed(reason, observations)
    }

    private fun describePlan(plan: NexusAiPlan): String = when (plan) {
        is NexusAiPlan.OpenApp -> "Открыть приложение «${plan.appName}»"
        is NexusAiPlan.TapText -> "Нажать на «${plan.exactLabel}»"
        is NexusAiPlan.EnterText -> "Ввести текст: ${plan.text.take(120)}"
        NexusAiPlan.GoBack -> "Вернуться на предыдущий экран"
        is NexusAiPlan.Scroll -> if (plan.direction == "FORWARD") "Прокрутить вниз" else "Прокрутить вверх"
        is NexusAiPlan.Wait -> "Подождать ${plan.milliseconds} мс"
        is NexusAiPlan.Answer -> "Подготовить ответ"
        is NexusAiPlan.Done -> "Завершить задачу"
        is NexusAiPlan.Unsupported -> "Остановиться: ${plan.reason}"
    }

    private fun signature(plan: NexusAiPlan): String = when (plan) {
        is NexusAiPlan.OpenApp -> "OPEN_APP:${plan.appName.lowercase()}"
        is NexusAiPlan.TapText -> "TAP_TEXT:${plan.exactLabel.lowercase()}"
        is NexusAiPlan.EnterText -> "ENTER_TEXT:${plan.text}"
        NexusAiPlan.GoBack -> "GO_BACK"
        is NexusAiPlan.Scroll -> "SCROLL:${plan.direction}"
        is NexusAiPlan.Wait -> "WAIT:${plan.milliseconds}"
        is NexusAiPlan.Answer -> "ANSWER:${plan.text}"
        is NexusAiPlan.Done -> "DONE:${plan.summary}"
        is NexusAiPlan.Unsupported -> "UNSUPPORTED:${plan.reason}"
    }
}
