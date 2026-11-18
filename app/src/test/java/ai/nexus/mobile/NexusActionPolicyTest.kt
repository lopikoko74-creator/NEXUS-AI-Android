package ai.nexus.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusActionPolicyTest {
    @Test
    fun navigationActionsDoNotRequireConfirmation() {
        assertTrue(NexusActionPolicy.mayExecute(NexusAction.GoBack, false))
        assertTrue(NexusActionPolicy.mayExecute(NexusAction.Scroll(true), false))
        assertTrue(NexusActionPolicy.mayExecute(NexusAction.OpenAccessibilitySettings, false))
    }

    @Test
    fun uiMutationsRequireConfirmation() {
        assertFalse(NexusActionPolicy.mayExecute(NexusAction.TapVisibleText("Continue"), false))
        assertTrue(NexusActionPolicy.mayExecute(NexusAction.TapVisibleText("Continue"), true))
        assertFalse(NexusActionPolicy.mayExecute(NexusAction.EnterText("hello"), false))
        assertTrue(NexusActionPolicy.mayExecute(NexusAction.EnterText("hello"), true))
    }

    @Test
    fun externalAndDestructiveActionsRequireConfirmation() {
        val actions = listOf<NexusAction>(
            NexusAction.SendMessage("Alex", "Hello"),
            NexusAction.MakeCall("+10000000000"),
            NexusAction.DeleteItem("document"),
            NexusAction.Purchase("item")
        )
        actions.forEach { action ->
            assertFalse(NexusActionPolicy.mayExecute(action, false))
            assertFalse(NexusActionPolicy.mayExecute(action, true))
        }
    }

    @Test
    fun invalidActionsAreRejectedEvenWhenConfirmed() {
        assertFalse(NexusActionPolicy.mayExecute(NexusAction.TapVisibleText(" "), true))
        assertFalse(NexusActionPolicy.mayExecute(NexusAction.EnterText(""), true))
        assertFalse(NexusActionPolicy.mayExecute(NexusAction.SendMessage("", "Hi"), true))
        assertFalse(NexusActionPolicy.mayExecute(NexusAction.MakeCall(""), true))
    }
}
