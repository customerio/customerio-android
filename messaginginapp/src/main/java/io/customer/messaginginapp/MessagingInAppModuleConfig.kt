package io.customer.messaginginapp

import io.customer.messaginginapp.type.ColorScheme
import io.customer.messaginginapp.type.InAppEventListener
import io.customer.sdk.core.module.CustomerIOModuleConfig
import io.customer.sdk.data.model.Region

/**
 * In app messaging module configurations that can be used to customize app
 * experience based on the provided configurations
 */
class MessagingInAppModuleConfig private constructor(
    val siteId: String,
    val region: Region,
    val eventListener: InAppEventListener?,
    val colorScheme: ColorScheme
) : CustomerIOModuleConfig {
    class Builder(
        private val siteId: String,
        private val region: Region
    ) : CustomerIOModuleConfig.Builder<MessagingInAppModuleConfig> {
        private var eventListener: InAppEventListener? = null
        private var colorScheme: ColorScheme = ColorScheme.AUTO

        fun setEventListener(eventListener: InAppEventListener): Builder {
            this.eventListener = eventListener
            return this
        }

        fun setColorScheme(colorScheme: ColorScheme): Builder {
            this.colorScheme = colorScheme
            return this
        }

        override fun build(): MessagingInAppModuleConfig {
            return MessagingInAppModuleConfig(
                siteId = siteId,
                region = region,
                eventListener = eventListener,
                colorScheme = colorScheme
            )
        }
    }
}
