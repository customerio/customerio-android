package io.customer.example

import android.app.Application
import io.customer.sdk.CustomerIo

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CustomerIo.Builder(
            siteId = "YOUR-SITE-ID",
            apiKey = "YOUR-API-KEY",
            appContext = this
        ).build()
    }
}
