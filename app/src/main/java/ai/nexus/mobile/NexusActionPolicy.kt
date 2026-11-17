package ai.nexus.mobile

/**
 * Типизированные действия агента. Модель не передаёт shell-команды или произвольный код.
 * Реальный исполнитель подключается только к явно перечисленным типам действий.
 */
sealed interface NexusAction {
    data object OpenAccessibilitySettings : NexusAction
    data object GoBack : NexusAction
    data class TapVisibleText(val exactLabel: String) : NexusAction
    data class EnterText(val text: String) : NexusAction
    data class SendMessage(val recipient: String, val text: String) : NexusAction
    data class MakeCall(val phoneNumber: String) : NexusAction
    data class DeleteItem(val description: String) : NexusAction
    data class Purchase(val description: String) : NexusAction
}

enum class ActionRisk {
    LOW,
    REVIEW,
    HIGH
}

data class ActionDecision(
    val allowed: Boolean,
    val requiresConfirmation: Boolean,
    val reason: String
)

/**
 * Fail-closed policy: unknown/invalid input is never executable.
 * External or destructive operations always require explicit user confirmation.
 */
object NexusActionPolicy {
    fun evaluate(action: NexusAction): ActionDecision = when (action) {
        NexusAction.OpenAccessibilitySettings,
        NexusAction.GoBack -> ActionDecision(
            allowed = true,
            requiresConfirmation = false,
            reason = "Локальное навигационное действие"
        )

        is NexusAction.TapVisibleText -> if (action.exactLabel.isBlank()) {
            ActionDecision(false, false, "Пустая метка элемента")
        } else {
            ActionDecision(true, true, "Нажатие может изменить состояние приложения")
        }

        is NexusAction.EnterText -> if (action.text.isEmpty()) {
            ActionDecision(false, false, "Пустой текст")
        } else {
            ActionDecision(true, true, "Ввод текста в активное поле")
        }

        is NexusAction.SendMessage -> if (
            action.recipient.isBlank() || action.text.isBlank()
        ) {
            ActionDecision(false, false, "Не указан получатель или текст")
        } else {
            ActionDecision(true, true, "Отправка сообщения — внешнее действие")
        }

        is NexusAction.MakeCall -> if (action.phoneNumber.isBlank()) {
            ActionDecision(false, false, "Не указан номер телефона")
        } else {
            ActionDecision(true, true, "Телефонный вызов требует подтверждения")
        }

        is NexusAction.DeleteItem -> if (action.description.isBlank()) {
            ActionDecision(false, false, "Не указано, что удалить")
        } else {
            ActionDecision(true, true, "Удаление может быть необратимым")
        }

        is NexusAction.Purchase -> if (action.description.isBlank()) {
            ActionDecision(false, false, "Не указана покупка")
        } else {
            ActionDecision(true, true, "Покупка требует явного подтверждения")
        }
    }

    fun mayExecute(action: NexusAction, userConfirmed: Boolean): Boolean {
        val decision = evaluate(action)
        return decision.allowed && (!decision.requiresConfirmation || userConfirmed)
    }
}
