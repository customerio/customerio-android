package io.customer.messaginginapp.gist.utilities

import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.sdk.core.di.SDKComponent

class ElapsedTimer {
    private var title: String = ""
    private var startTime: Long = 0
    private val inAppMessagingManager = SDKComponent.inAppMessagingManager

    fun start(title: String) {
        this.title = title
        this.startTime = System.currentTimeMillis()
        inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("Timer started for $title"))
    }

    fun end() {
        if (startTime > 0) {
            val timeElapsed = (System.currentTimeMillis() - startTime) / 1000.0
            inAppMessagingManager.dispatch(InAppMessagingAction.LogEvent("Timer ended for $title in $timeElapsed seconds"))
            startTime = 0
        }
    }
}
