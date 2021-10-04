package io.customer.sdk

import android.content.Context
import io.customer.sdk.api.CustomerIoApi
import io.customer.sdk.api.model.Region

class CustomerIoConfig(
    val siteId: String,
    val apiKey: String,
    val region: Region,
    val timeout: Long
)

class CustomerIo internal constructor(
    val config: CustomerIoConfig,
    private val api: CustomerIoApi,
) {
    companion object {
        private var instance: CustomerIo? = null

        @JvmStatic
        fun instance(): CustomerIo {
            return instance
                ?: throw IllegalStateException("CustomerIo.Builder::build() must be called before obtaining CustomerIo instance")
        }
    }

    class Builder(
        private val siteId: String,
        private val apiKey: String,
        private var region: Region = Region.US,
        private val appContext: Context
    ) {
        private var timeout = 10L

        fun setRegion(region: Region): Builder {
            this.region = region
            return this
        }

        fun setTimeout(timeout: Long): Builder {
            this.timeout = timeout
            return this
        }

        fun build(): CustomerIo {

            if (apiKey.isEmpty()) {
                throw IllegalStateException("apiKey is not defined in " + this::class.java.simpleName)
            }

            if (siteId.isEmpty()) {
                throw IllegalStateException("siteId is not defined in " + this::class.java.simpleName)
            }

            val config = CustomerIoConfig(
                siteId = siteId,
                apiKey = apiKey,
                region = region,
                timeout = timeout
            )

            val customerIoComponent = CustomerIoComponent(config)

            val client = CustomerIo(config, customerIoComponent.buildApi())
            instance = client
            return client
        }
    }

    fun identify(identifier: String) {
        api.identify(identifier)
    }
}
