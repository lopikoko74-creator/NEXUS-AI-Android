package ai.nexus.mobile

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Ограниченная точка интеграции с Android UI.
 * Не выполняет произвольный код и пока не принимает команды от модели.
 */
class NexusAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Сбор содержимого окон отключён по умолчанию.
    }

    override fun onInterrupt() = Unit

    fun clickVisibleText(label: String): Boolean {
        if (label.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        return findAndClick(root, label)
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