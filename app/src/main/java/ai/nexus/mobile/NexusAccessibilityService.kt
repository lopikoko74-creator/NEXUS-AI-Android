package ai.nexus.mobile

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * UI access is read only unless an explicit, policy-checked action is requested.
 * Window text is collected only during an active agent task, never in the background.
 */
class NexusAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    fun executeApprovedAction(action: NexusAction, userConfirmed: Boolean): Boolean {
        if (!NexusActionPolicy.mayExecute(action, userConfirmed)) return false
        return when (action) {
            NexusAction.OpenAccessibilitySettings -> {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }
            NexusAction.GoBack -> performGlobalAction(GLOBAL_ACTION_BACK)
            is NexusAction.TapVisibleText -> clickVisibleText(action.exactLabel)
            is NexusAction.EnterText -> setTextOnFocusedNode(action.text)
            is NexusAction.SendMessage,
            is NexusAction.MakeCall,
            is NexusAction.DeleteItem,
            is NexusAction.Purchase -> false
        }
    }

    /** Explicit, bounded snapshot used as evidence for the next planner turn. */
    fun describeActiveWindow(maxItems: Int = 35): String {
        val root = rootInActiveWindow ?: return "No active accessibility window is available."
        val labels = ArrayList<String>()
        collectVisibleLabels(root, labels, maxItems.coerceIn(1, 60))
        return labels.distinct().joinToString(" | ").ifBlank { "Active window contains no readable labels." }
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
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
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

    private fun collectVisibleLabels(node: AccessibilityNodeInfo, out: MutableList<String>, limit: Int) {
        if (out.size >= limit) return
        if (node.isVisibleToUser) {
            val label = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (label.isNotEmpty() && label.length <= 160) out += label
        }
        for (i in 0 until node.childCount) {
            if (out.size >= limit) break
            node.getChild(i)?.let { collectVisibleLabels(it, out, limit) }
        }
    }

    companion object {
        @Volatile var current: NexusAccessibilityService? = null
            private set
    }
}
