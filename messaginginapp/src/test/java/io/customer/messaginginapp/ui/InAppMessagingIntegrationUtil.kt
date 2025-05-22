package io.customer.messaginginapp.ui

import io.customer.commontest.extensions.flushCoroutines
import io.customer.commontest.util.ScopeProviderStub
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager

object InAppMessagingIntegrationUtil {
    internal fun Message.testMatchAndEmbed(
        elementId: String,
        manager: InAppMessagingManager,
        routeName: String,
        scopeProvider: ScopeProviderStub
    ) {
        manager.dispatch(InAppMessagingAction.SetPageRoute(routeName))
            .flushCoroutines(scopeProvider.inAppLifecycleScope)

        manager.dispatch(InAppMessagingAction.EmbedMessages(listOf(this)))
            .flushCoroutines(scopeProvider.inAppLifecycleScope)
    }

    internal fun Message.testTap(
        manager: InAppMessagingManager,
        routeName: String,
        actionName: String,
        buttonName: String,
        scopeProvider: ScopeProviderStub
    ) {
        manager.dispatch(
            InAppMessagingAction.EngineAction.Tap(
                message = this,
                route = routeName,
                action = actionName,
                name = buttonName
            )
        ).flushCoroutines(scopeProvider.inAppLifecycleScope)
    }

    internal fun Message.testDisplay(
        manager: InAppMessagingManager,
        scopeProvider: ScopeProviderStub
    ) {
        manager.dispatch(InAppMessagingAction.DisplayMessage(this))
            .flushCoroutines(scopeProvider.inAppLifecycleScope)
    }

    internal fun Message.testDismiss(
        manager: InAppMessagingManager,
        scopeProvider: ScopeProviderStub
    ) {
        manager.dispatch(InAppMessagingAction.DismissMessage(this))
            .flushCoroutines(scopeProvider.inAppLifecycleScope)
    }

    internal fun Message.testLoadingFailed(
        manager: InAppMessagingManager,
        scopeProvider: ScopeProviderStub
    ) {
        manager.dispatch(InAppMessagingAction.EngineAction.MessageLoadingFailed(this))
            .flushCoroutines(scopeProvider.inAppLifecycleScope)
    }
}
