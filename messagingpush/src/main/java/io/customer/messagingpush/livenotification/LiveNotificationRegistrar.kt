package io.customer.messagingpush.livenotification

import io.customer.messagingpush.di.pushModuleConfig
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.SDKComponent.eventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends `register_push_to_start` track events for the live-notification activity
 * types the host app enabled via `MessagingPushModuleConfig.setLiveNotificationTypes`.
 * No types enabled ⇒ nothing is registered.
 *
 * Registration is (re)attempted whenever the device token rotates
 * ([Event.RegisterDeviceTokenEvent]) or the user changes
 * ([Event.UserChangedEvent]), and is deduped per activity type via
 * [LiveNotificationStore] (signature = `token|userId`). The signature is only
 * stored when the event is actually emitted, so a type left unregistered re-fires
 * on the next attempt. Token deletion / reset clears the stored signatures so the
 * next token re-registers.
 *
 * Live notifications are auth-only. We track identification from
 * [Event.UserChangedEvent] rather than gating on the pipeline alone, and
 * [registerAll] retries a few times: the underlying track event drops while the
 * user isn't yet identified, and the pipeline's identified state can lag the
 * [Event.UserChangedEvent] that triggered registration by a few ms.
 */
internal class LiveNotificationRegistrar(
    private val client: LiveNotificationLifecycleClient,
    private val store: LiveNotificationStore
) {

    @Volatile
    private var token: String? = null

    @Volatile
    private var userId: String = ""

    @Volatile
    private var identified: Boolean = false

    private val scope = CoroutineScope(SDKComponent.dispatchersProvider.background)

    // Serialises registration passes so the token and user-changed events can't run two
    // concurrent loops against the shared dedup store (which would double-emit).
    private val registerMutex = Mutex()

    private val enabledTypes: Set<String>
        get() = SDKComponent.pushModuleConfig.liveNotificationTypes

    fun start() {
        // Drop dedup entries for activities that ended long ago without an explicit `end`.
        store.trimStaleTimestamps()

        eventBus.subscribe(Event.RegisterDeviceTokenEvent::class) { event ->
            token = event.token
            if (identified) registerAll()
        }
        eventBus.subscribe(Event.UserChangedEvent::class) { event ->
            identified = event.userId != null
            userId = event.userId ?: event.anonymousId
            if (identified) registerAll()
        }
        eventBus.subscribe(Event.DeleteDeviceTokenEvent::class) {
            token = null
            store.clearRegistrations()
        }
        eventBus.subscribe(Event.ResetEvent::class) {
            identified = false
            store.clearRegistrations()
        }
    }

    /**
     * Registers [activityType] immediately on-demand, independent of the
     * auto-registration dedup (the host app explicitly asked). Uses the freshest
     * device token (the registrar's, falling back to the persisted token).
     * Returns true if the `register_push_to_start` event was emitted (a token is
     * available and the user is identified).
     */
    fun register(activityType: String): Boolean {
        val currentToken = token ?: SDKComponent.android().globalPreferenceStore.getDeviceToken()
        if (currentToken.isNullOrBlank()) {
            SDKComponent.logger.debug(
                "No FCM token available yet; cannot register live notification type '$activityType'."
            )
            return false
        }
        val emitted = client.registerPushToStart(activityType, currentToken)
        if (emitted) {
            store.setRegistrationSignature(activityType, "$currentToken|$userId")
        }
        return emitted
    }

    /**
     * Registers every enabled type not already registered for the current
     * `token|userId`, retrying with backoff so registration survives the brief
     * window where the pipeline hasn't yet applied the identify that triggered it.
     */
    private fun registerAll() {
        scope.launch {
            registerMutex.withLock {
                var attempt = 0
                while (attempt < MAX_ATTEMPTS) {
                    attempt++
                    val currentToken = token ?: return@withLock
                    val signature = "$currentToken|$userId"
                    val pending = enabledTypes.filter { store.registrationSignature(it) != signature }
                    if (pending.isEmpty()) return@withLock

                    for (activityType in pending) {
                        if (client.registerPushToStart(activityType, currentToken)) {
                            store.setRegistrationSignature(activityType, signature)
                        }
                    }
                    // Stop as soon as everything registered; otherwise wait for the
                    // identify/token state to settle and retry the stragglers.
                    if (enabledTypes.all { store.registrationSignature(it) == signature }) return@withLock
                    if (attempt < MAX_ATTEMPTS) delay(RETRY_DELAY_MS)
                }
                SDKComponent.logger.debug(
                    "Live notification registration incomplete after $MAX_ATTEMPTS attempts; " +
                        "will retry on next token/user change."
                )
            }
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 500L
    }
}
