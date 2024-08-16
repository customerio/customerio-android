package io.customer.messaginginapp.di

import android.webkit.WebView
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.domain.InAppMessagingManager
import io.customer.messaginginapp.gist.data.listeners.GistQueue
import io.customer.messaginginapp.gist.data.listeners.Queue
import io.customer.messaginginapp.gist.presentation.engine.EngineWebViewClientInterceptor
import io.customer.messaginginapp.provider.GistApi
import io.customer.messaginginapp.provider.GistApiProvider
import io.customer.messaginginapp.provider.GistInAppMessagesProvider
import io.customer.messaginginapp.provider.InAppMessagesProvider
import io.customer.sdk.CustomerIOInstance
import io.customer.sdk.core.di.SDKComponent

internal val SDKComponent.gistApiProvider: GistApi
    get() = newInstance<GistApi> { GistApiProvider() }

internal val SDKComponent.gistProvider: InAppMessagesProvider
    get() = singleton<InAppMessagesProvider> { GistInAppMessagesProvider(gistApiProvider) }

@Suppress("UnusedReceiverParameter")
fun CustomerIOInstance.inAppMessaging(): ModuleMessagingInApp {
    return ModuleMessagingInApp.instance()
}

internal val SDKComponent.inAppMessagingManager: InAppMessagingManager
    get() = singleton<InAppMessagingManager> { InAppMessagingManager }

internal val SDKComponent.gistQueue: GistQueue
    get() = singleton<GistQueue> { Queue() }

internal val SDKComponent.engineWebViewClientInterceptor: EngineWebViewClientInterceptor
    get() = singleton<EngineWebViewClientInterceptor> {
        object : EngineWebViewClientInterceptor {
            override fun onPageFinished(view: WebView, url: String?) {
            }
        }
    }
