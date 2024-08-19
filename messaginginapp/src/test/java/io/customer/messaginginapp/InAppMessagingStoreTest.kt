package io.customer.messaginginapp

import io.customer.commontest.core.RobolectricTest
import io.customer.commontest.extensions.random
import io.customer.messaginginapp.domain.InAppMessagingAction
import io.customer.messaginginapp.domain.InAppMessagingManager
import io.customer.messaginginapp.domain.MessageState
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.model.Message
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InAppMessagingStoreTest : RobolectricTest() {

    private val manager = InAppMessagingManager

    private fun initializeAndSetUser() {
        manager.dispatch(InAppMessagingAction.Initialize(siteId = String.random, dataCenter = String.random, context = applicationMock, environment = GistEnvironment.PROD))
        manager.dispatch(InAppMessagingAction.SetUser(String.random))
    }

    @Test
    fun `test ProcessMessages with random messages`() = runTest {
        val messages = listOf(
            Message(queueId = "1", priority = 2),
            Message(queueId = "2", priority = 1),
            Message(queueId = "3", priority = 3)
        )

        initializeAndSetUser()
        manager.dispatch(InAppMessagingAction.ProcessMessages(messages))

        val state = manager.getCurrentState()
        state.messagesInQueue.size shouldBeEqualTo 3
        (state.currentMessageState as? MessageState.Processing)?.message?.queueId shouldBeEqualTo "2"
    }

    @Test
    fun `test ProcessMessages with duplicate messages`() = runTest {
        val messages = listOf(
            Message(queueId = "1"),
            Message(queueId = "1"),
            Message(queueId = "2")
        )

        initializeAndSetUser()
        manager.dispatch(InAppMessagingAction.ProcessMessages(messages))

        val state = manager.getCurrentState()
        state.messagesInQueue.size shouldBeEqualTo 2
        state.messagesInQueue.any { it.queueId == "1" } shouldBe true
        state.messagesInQueue.any { it.queueId == "2" } shouldBe true
    }

    @Test
    fun `test route change`() = runTest {
        initializeAndSetUser()

        val message = Message(queueId = "1", properties = mapOf("routeRuleAndroid" to "home"))
        manager.dispatch(InAppMessagingAction.ProcessMessages(listOf(message)))
        manager.dispatch(InAppMessagingAction.SetCurrentRoute("home"))

        var state = manager.getCurrentState()
        state.currentRoute shouldBe "home"
        (state.currentMessageState as? MessageState.Processing)?.message?.queueId shouldBe "1"

        manager.dispatch(InAppMessagingAction.SetCurrentRoute("profile"))
        state = manager.getCurrentState()
        state.currentRoute shouldBe "profile"
        state.currentMessageState shouldBe MessageState.Default
    }

    @Test
    fun `test message visibility based on route`() = runTest {
        initializeAndSetUser()

        val homeMessage = Message(queueId = "1", properties = mapOf("routeRuleAndroid" to "home"))
        val profileMessage = Message(queueId = "1", properties = mapOf("routeRuleAndroid" to "profile"))
        val generalMessage = Message(queueId = "3")

        manager.dispatch(InAppMessagingAction.ProcessMessages(listOf(homeMessage, profileMessage, generalMessage)))
        manager.dispatch(InAppMessagingAction.SetCurrentRoute("home"))

        var state = manager.getCurrentState()
        (state.currentMessageState as? MessageState.Processing)?.message?.queueId shouldBe "1"

        manager.dispatch(InAppMessagingAction.SetCurrentRoute("profile"))
        state = manager.getCurrentState()
        (state.currentMessageState as? MessageState.Processing)?.message?.queueId shouldBe "2"

        manager.dispatch(InAppMessagingAction.SetCurrentRoute("settings"))
        state = manager.getCurrentState()
        (state.currentMessageState as? MessageState.Processing)?.message?.queueId shouldBe "3"
    }

    @Test
    fun `test message dismissal`() = runTest {
//        initializeAndSetUser()
//
//        val message = Message(queueId = "1")
//        manager.dispatch(InAppMessagingAction.ProcessMessages(listOf(message)))
//        manager.dispatch(InAppMessagingAction.MakeMessageVisible(message))
//        manager.dispatch(InAppMessagingAction.DismissMessage(message, shouldLog = true, viaCloseAction = true))
//
//        val state = manager.getCurrentState()
//        state.currentMessageState shouldBe MessageState.Dismissed::class
//        state.shownMessageQueueIds.contains("1") shouldBe true
    }

    @Test
    fun `test polling interval change`() = runTest {
        initializeAndSetUser()

        manager.dispatch(InAppMessagingAction.SetPollingInterval(300_000L))

        val state = manager.getCurrentState()
        state.pollInterval shouldBe 300_000L
    }
}
