package ai.nexus.mobile

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Ограниченная точка интеграции с Android UI.
 * Не читает содержимое окон в фоне и не исполняет произвольный код.
 */
class NexusAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Сбор содержимого окон отключён по умолчанию.
    }

    override fun onInterrupt() = Unit

    /**
     * Единственная точка исполнения команд.
     * Действие сначала проходит policy gate, затем — allow-list ниже.
     */
    fun executeApprovedAction(action: NexusAction, userConfirmed: Boolean): Boolean {
        if (!NexusActionPolicy.mayExecute(action, userConfirmed)) return false

        return when (action) {
            NexusAction.OpenAccessibilitySettings -> {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }
            NexusAction.GoBack -> performGlobalAction(GLOBAL_ACTION_BACK)
            is NexusAction.TapVisibleText -> clickVisibleText(action.exactLabel)
            is NexusAction.EnterText -> setTextOnFocusedNode(action.text)
            // Эти действия намеренно не исполняются: для них ещё нет отдельных
            // проверенных Android-интеграций и экранов подтверждения.
            is NexusAction.SendMessage,
            is NexusAction.MakeCall,
            is NexusAction.DeleteItem,
            is NexusAction.Purchase -> false
        }
    }

    fun clickVisibleText(label: String): Boolean {
        if (label.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        return findAndClick(root, label)
    }

    private fun setTextOnFocusedNode(value: String): Boolean {
        if (value.isEmpty()) return false
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!focused.isEnabled || !focused.isFocusable || !focused.isEditable) return false
        val args = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value
            )
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findAndClick(node: AccessibilityNodeInfo, label: String): Boolean {
        if ((node.text?.toString() == label || node.contentDescription?.toString() == label)
            && node.isEnabled && node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClick(child, label)) return true
        }
        return false
    }
}
