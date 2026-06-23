package io.customer.messaginginapp.inbox.data

import io.customer.messaginginapp.gist.data.listeners.GistQueue
import io.customer.messaginginapp.gist.data.model.InboxMessage
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.store.InAppPreferenceStore
import io.customer.messaginginapp.testutils.extension.createInboxMessage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Test

/**
 * Visibility-policy coverage for [InboxRepository]: the inbox is VISIBLE iff
 * enabled + >=1 selected message + templates + branding (each fresh OR stale),
 * else HIDDEN — never an error. Also covers branding/templates serve-stale (from
 * the last-persisted HTTP-cache-backed value) and messages-from-headless-state.
 *
 * Freshness coverage (once-per-session revalidation gate): the first load of a
 * session revalidates (conditional GET) even when both assets are persisted; a
 * second load in the same session serves the persisted value with no network
 * call; and a failed revalidation serves the last-persisted (stale) value.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxRepositoryTest {

    private val baseUrl = "https://test.inapp.customer.io"
    private val templatesUrl = "$baseUrl/api/v1/templates"
    private val brandingUrl = "$baseUrl/api/v1/branding"

    private val branding = Branding(theme = mapOf("color" to "blue"))
    private val brandingJson = "{\"theme\":{\"color\":\"blue\"}}"
    private val templatesJson = "{\"basic\":[]}"

    private fun message(): InboxMessage =
        createInboxMessage(deliveryId = "m1", topics = listOf("cio_inbox"))

    private fun managerWith(
        enabled: Boolean,
        messages: Set<InboxMessage> = emptySet()
    ): InAppMessagingManager {
        val manager = mockk<InAppMessagingManager>()
        val state = InAppMessagingState(
            userId = "user-1",
            isInboxEnabled = enabled,
            inboxMessages = messages
        )
        every { manager.getCurrentState() } returns state
        return manager
    }

    /**
     * Mutable fake of the HTTP-cache-backed network-response store, keyed by URL.
     * Templates/branding are read back through [InAppPreferenceStore.getNetworkResponse].
     */
    private fun preferenceStoreWith(
        templates: String? = null,
        branding: String? = null
    ): InAppPreferenceStore {
        val store = mockk<InAppPreferenceStore>(relaxed = true)
        every { store.getNetworkResponse(templatesUrl) } returns templates
        every { store.getNetworkResponse(brandingUrl) } returns branding
        return store
    }

    private fun gistQueue(): GistQueue {
        val queue = mockk<GistQueue>(relaxed = true)
        every { queue.baseUrl } returns baseUrl
        return queue
    }

    private fun repository(
        api: InboxApi,
        manager: InAppMessagingManager,
        preferenceStore: InAppPreferenceStore = preferenceStoreWith()
    ): InboxRepository = InboxRepository(
        api = api,
        inAppMessagingManager = manager,
        preferenceStore = preferenceStore,
        gistQueue = gistQueue(),
        retryPolicy = RetryPolicy(maxAttempts = 1, baseDelayMillis = 0L),
        logger = mockk(relaxed = true)
    )

    // --- (a) enabled + messages + templates + branding present -> VISIBLE ---

    @Test
    fun computeVisibility_givenAllPresentAndFresh_expectVisible() = runTest {
        val api = mockk<InboxApi>()
        coEvery { api.fetchTemplatesRaw() } returns templatesJson
        coEvery { api.fetchBranding() } returns branding
        // After a successful fetch the gist interceptor persists both responses; model that
        // by having the store return them on the post-fetch read used by computeVisibility.
        val store = preferenceStoreWith(templates = templatesJson, branding = brandingJson)
        val manager = managerWith(enabled = true, messages = setOf(message()))
        val repo = repository(api, manager, store)

        repo.loadTemplatesAndBranding().shouldBeInstanceOf<InboxFetchOutcome.Visible>()

        val visibility = repo.computeVisibility()
        visibility.shouldBeInstanceOf<InboxVisibility.Visible>()
        val visible = visibility as InboxVisibility.Visible
        visible.templatesJson shouldBeEqualTo templatesJson
        visible.branding shouldBeEqualTo branding
        visible.messages.map { it.deliveryId } shouldBeEqualTo listOf("m1")
    }

    // --- (b) any one missing and uncached -> HIDDEN (not error) ---

    @Test
    fun computeVisibility_givenDisabled_expectHidden() = runTest {
        val api = mockk<InboxApi>(relaxed = true)
        val manager = managerWith(enabled = false, messages = setOf(message()))
        val repo = repository(api, manager, preferenceStoreWith(templatesJson, brandingJson))

        val visibility = repo.computeVisibility()
        visibility.shouldBeInstanceOf<InboxVisibility.Hidden>()
        // Diagnostic reason aligned BYTE-FOR-BYTE with iOS.
        (visibility as InboxVisibility.Hidden).reason shouldBeEqualTo "inbox disabled"
        repo.isInboxVisible shouldBeEqualTo false
    }

    @Test
    fun computeVisibility_givenNoMessages_expectHidden() = runTest {
        val api = mockk<InboxApi>(relaxed = true)
        val manager = managerWith(enabled = true, messages = emptySet())
        val repo = repository(api, manager, preferenceStoreWith(templatesJson, brandingJson))

        val visibility = repo.computeVisibility()
        visibility.shouldBeInstanceOf<InboxVisibility.Hidden>()
        (visibility as InboxVisibility.Hidden).reason shouldBeEqualTo "no selected messages"
    }

    @Test
    fun loadTemplatesAndBranding_givenBrandingFailsAndUncached_expectHiddenNotError() = runTest {
        val api = mockk<InboxApi>()
        coEvery { api.fetchTemplatesRaw() } returns templatesJson
        coEvery { api.fetchBranding() } throws InboxFetchException("branding down")
        // Nothing persisted -> no stale fallback for branding.
        val manager = managerWith(enabled = true, messages = setOf(message()))
        val repo = repository(api, manager)

        // Branding is required-to-render: a failed, uncached branding -> Hidden.
        val outcome = repo.loadTemplatesAndBranding()
        outcome.shouldBeInstanceOf<InboxFetchOutcome.Hidden>()
        (outcome as InboxFetchOutcome.Hidden).reason shouldBeEqualTo "branding unavailable"
    }

    @Test
    fun loadTemplatesAndBranding_givenTemplatesFailsAndUncached_expectHidden() = runTest {
        val api = mockk<InboxApi>()
        coEvery { api.fetchTemplatesRaw() } throws InboxFetchException("templates down")
        coEvery { api.fetchBranding() } returns branding
        val manager = managerWith(enabled = true, messages = setOf(message()))
        val repo = repository(api, manager)

        val outcome = repo.loadTemplatesAndBranding()
        outcome.shouldBeInstanceOf<InboxFetchOutcome.Hidden>()
        (outcome as InboxFetchOutcome.Hidden).reason shouldBeEqualTo "templates unavailable"
    }

    // --- (c) all serve from stale (last-persisted) -> still VISIBLE ---

    @Test
    fun computeVisibility_givenAllServeFromStale_expectVisible() = runTest {
        // Templates + branding are already persisted (last-known); the network fails for both,
        // so serve-stale (the persisted values) must keep the inbox visible.
        val store = preferenceStoreWith(templates = templatesJson, branding = brandingJson)
        val api = mockk<InboxApi>()
        coEvery { api.fetchTemplatesRaw() } throws InboxFetchException("offline")
        coEvery { api.fetchBranding() } throws InboxFetchException("offline")
        // Messages still come from the headless store (retained across a failed poll).
        val manager = managerWith(enabled = true, messages = setOf(message()))
        val repo = repository(api, manager, store)

        val outcome = repo.loadTemplatesAndBranding()
        // First load of the session revalidates (both fetches throw); serve-stale from the
        // persisted values keeps the inbox Visible(fromCache=true).
        outcome.shouldBeInstanceOf<InboxFetchOutcome.Visible>()
        (outcome as InboxFetchOutcome.Visible).fromCache shouldBeEqualTo true

        val visibility = repo.computeVisibility()
        visibility.shouldBeInstanceOf<InboxVisibility.Visible>()
        (visibility as InboxVisibility.Visible).messages.map { it.deliveryId } shouldBeEqualTo listOf("m1")
    }

    @Test
    fun loadTemplatesAndBranding_givenFetchFailsButPersistedExists_expectServedStaleVisible() = runTest {
        // Templates persisted, branding NOT -> the fetch path runs (branding missing). Branding
        // fetch fails; templates fetch also fails but the persisted templates serve stale, and
        // here we persist branding too so the whole thing resolves Visible(fromCache=true).
        val store = preferenceStoreWith(templates = templatesJson, branding = brandingJson)
        val api = mockk<InboxApi>()
        coEvery { api.fetchTemplatesRaw() } throws InboxFetchException("offline")
        coEvery { api.fetchBranding() } throws InboxFetchException("offline")
        val manager = managerWith(enabled = true, messages = setOf(message()))
        val repo = repository(api, manager, store)

        val outcome = repo.loadTemplatesAndBranding()
        outcome.shouldBeInstanceOf<InboxFetchOutcome.Visible>()
        (outcome as InboxFetchOutcome.Visible).fromCache shouldBeEqualTo true
    }

    // --- needsTemplatesOrBrandingFetch drives the poll-time fetch-if-missing trigger ---

    @Test
    fun needsTemplatesOrBrandingFetch_givenEmptyCache_expectTrue() = runTest {
        val manager = managerWith(enabled = true, messages = setOf(message()))
        val repo = repository(mockk(relaxed = true), manager)

        repo.needsTemplatesOrBrandingFetch() shouldBeEqualTo true
    }

    @Test
    fun needsTemplatesOrBrandingFetch_givenBothPersisted_expectFalse() = runTest {
        val store = preferenceStoreWith(templates = templatesJson, branding = brandingJson)
        val manager = managerWith(enabled = true, messages = setOf(message()))
        val repo = repository(mockk(relaxed = true), manager, store)

        repo.needsTemplatesOrBrandingFetch() shouldBeEqualTo false
    }

    // --- in-flight guard prevents duplicate concurrent fetches ---

    @Test
    fun loadTemplatesAndBranding_givenConcurrentCalls_expectSingleNetworkFetch() = runTest {
        // Gate the templates fetch so the first call is still "in-flight" when the
        // second concurrent call starts; the guard must short-circuit the second.
        val templatesGate = CompletableDeferred<Unit>()
        val templatesCalls = AtomicInteger(0)
        val brandingCalls = AtomicInteger(0)

        val api = mockk<InboxApi>()
        coEvery { api.fetchTemplatesRaw() } coAnswers {
            templatesCalls.incrementAndGet()
            templatesGate.await()
            templatesJson
        }
        coEvery { api.fetchBranding() } coAnswers {
            brandingCalls.incrementAndGet()
            branding
        }
        val manager = managerWith(enabled = true, messages = setOf(message()))
        val repo = repository(api, manager)

        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val first = async(dispatcher) { repo.loadTemplatesAndBranding() }
        // Second call begins while the first is parked on templatesGate.
        val second = async(dispatcher) { repo.loadTemplatesAndBranding() }

        // The guard should have short-circuited the second call (it returned the
        // current outcome) without launching a second network fetch.
        templatesGate.complete(Unit)
        first.await().shouldBeInstanceOf<InboxFetchOutcome.Visible>()
        second.await()

        // Exactly one network fetch per endpoint despite two concurrent callers.
        templatesCalls.get() shouldBeEqualTo 1
        brandingCalls.get() shouldBeEqualTo 1
        repo.isFetchInFlight shouldBeEqualTo false
    }

    // --- messages are read from the headless store (serve-stale = store retention) ---

    @Test
    fun selectVisualInboxMessages_givenHeadlessStateMessages_expectSelected() = runTest {
        val api = mockk<InboxApi>(relaxed = true)
        val manager = managerWith(enabled = true, messages = setOf(message()))
        val repo = repository(api, manager)

        // No separate message cache: selection reads straight from state.inboxMessages.
        repo.selectVisualInboxMessages().map { it.deliveryId } shouldBeEqualTo listOf("m1")
    }

    @Test
    fun selectVisualInboxMessages_givenEmptyHeadlessState_expectEmpty() = runTest {
        val api = mockk<InboxApi>(relaxed = true)
        val manager = managerWith(enabled = true, messages = emptySet())
        val repo = repository(api, manager)

        repo.selectVisualInboxMessages() shouldBeEqualTo emptyList()
    }

    // --- once-per-session revalidation gate ---

    /**
     * Builds an [InboxApi] mock that counts calls and always returns the persisted
     * fixtures. The conditional GET (304/200) is modeled as a successful fetch.
     */
    private fun countingApi(
        templatesCalls: AtomicInteger,
        brandingCalls: AtomicInteger
    ): InboxApi {
        val api = mockk<InboxApi>()
        coEvery { api.fetchTemplatesRaw() } coAnswers {
            templatesCalls.incrementAndGet()
            templatesJson
        }
        coEvery { api.fetchBranding() } coAnswers {
            brandingCalls.incrementAndGet()
            branding
        }
        return api
    }

    // (a) First load of a session revalidates even when both assets are already persisted
    // (conditional GET -> 304/200), i.e. it hits the network rather than serving cache blindly.
    @Test
    fun loadTemplatesAndBranding_givenFirstLoadWithBothPersisted_expectRevalidatesOverNetwork() = runTest {
        val templatesCalls = AtomicInteger(0)
        val brandingCalls = AtomicInteger(0)
        val store = preferenceStoreWith(templates = templatesJson, branding = brandingJson)
        val manager = managerWith(enabled = true, messages = setOf(message()))
        val repo = repository(countingApi(templatesCalls, brandingCalls), manager, store)

        val outcome = repo.loadTemplatesAndBranding()

        outcome.shouldBeInstanceOf<InboxFetchOutcome.Visible>()
        // Network WAS hit despite both assets being persisted: this is the revalidation.
        templatesCalls.get() shouldBeEqualTo 1
        brandingCalls.get() shouldBeEqualTo 1
    }

    // (b) A second load in the same session, with both assets persisted, serves from cache
    // WITHOUT a second network call (the gate is closed after the first revalidation).
    @Test
    fun loadTemplatesAndBranding_givenSecondLoadSameSession_expectServesCachedNoNetwork() = runTest {
        val templatesCalls = AtomicInteger(0)
        val brandingCalls = AtomicInteger(0)
        val store = preferenceStoreWith(templates = templatesJson, branding = brandingJson)
        val manager = managerWith(enabled = true, messages = setOf(message()))
        val repo = repository(countingApi(templatesCalls, brandingCalls), manager, store)

        repo.loadTemplatesAndBranding() // first load: revalidates (1 call each)
        val second = repo.loadTemplatesAndBranding() // same session: must serve cache

        second.shouldBeInstanceOf<InboxFetchOutcome.Visible>()
        (second as InboxFetchOutcome.Visible).fromCache shouldBeEqualTo true
        // No SECOND network call: still exactly one fetch per endpoint across both loads.
        templatesCalls.get() shouldBeEqualTo 1
        brandingCalls.get() shouldBeEqualTo 1
    }

    // (c) A failed revalidation serves the last-persisted (stale) value and closes the gate,
    // so the next same-session load serves cache without retrying the network.
    @Test
    fun loadTemplatesAndBranding_givenRevalidationFails_expectServeStaleAndGateClosed() = runTest {
        val templatesCalls = AtomicInteger(0)
        val brandingCalls = AtomicInteger(0)
        val store = preferenceStoreWith(templates = templatesJson, branding = brandingJson)
        val api = mockk<InboxApi>()
        coEvery { api.fetchTemplatesRaw() } coAnswers {
            templatesCalls.incrementAndGet()
            throw InboxFetchException("offline")
        }
        coEvery { api.fetchBranding() } coAnswers {
            brandingCalls.incrementAndGet()
            throw InboxFetchException("offline")
        }
        val manager = managerWith(enabled = true, messages = setOf(message()))
        val repo = repository(api, manager, store)

        val first = repo.loadTemplatesAndBranding()
        // Revalidation attempted (1 call each) but failed -> serve stale persisted -> Visible.
        first.shouldBeInstanceOf<InboxFetchOutcome.Visible>()
        (first as InboxFetchOutcome.Visible).fromCache shouldBeEqualTo true
        templatesCalls.get() shouldBeEqualTo 1
        brandingCalls.get() shouldBeEqualTo 1

        val second = repo.loadTemplatesAndBranding()
        // Gate is closed after the attempt: no retry on the next same-session load.
        second.shouldBeInstanceOf<InboxFetchOutcome.Visible>()
        templatesCalls.get() shouldBeEqualTo 1
        brandingCalls.get() shouldBeEqualTo 1
    }
}
