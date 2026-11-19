package ai.nexus.mobile

/**
 * Keeps a pending, policy-gated action available while the foreground service
 * hands control to the user-facing confirmation screen in the same process.
 */
object NexusPendingActionStore {
    @Volatile var pending: NexusPendingConfirmation? = null
}