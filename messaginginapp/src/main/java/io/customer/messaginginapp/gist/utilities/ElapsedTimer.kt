package io.customer.messaginginapp.gist.utilities

import io.customer.sdk.core.di.SDKComponent

class ElapsedTimer {
    private var title: String = ""
    private var startTime: Long = 0
    private val logger = SDKComponent.logger

    fun start(title: String) {
        this.title = title
        this.startTime = System.currentTimeMillis()
        logger.debug("Timer started for $title")
    }

    fun end() {
        if (startTime > 0) {
            val timeElapsed = (System.currentTimeMillis() - startTime) / 1000.0
            logger.debug("Timer ended for $title in $timeElapsed seconds")
            startTime = 0
        }
    }
}
