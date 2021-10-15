package io.customer.example

import android.app.Application
import io.customer.sdk.CustomerIO

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CustomerIO.Builder(
            siteId = "YOUR-SITE-ID",
            apiKey = "YOUR-API-KEY",
            appContext = this
        ).build()
    }
}
