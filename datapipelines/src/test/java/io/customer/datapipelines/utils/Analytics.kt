package io.customer.datapipelines.utils

import android.app.Application
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.Configuration
import com.segment.analytics.kotlin.core.Connection
import com.segment.analytics.kotlin.core.ErrorHandler
import com.segment.analytics.kotlin.core.HTTPClient
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.datapipelines.extensions.updateAnalyticsConfig
import io.customer.datapipelines.stubs.TestCoroutineConfiguration
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.spyk
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import sovran.kotlin.Store

val settingsDefault = """
    {
      "integrations": {
        "Customer.io Data Pipelines": {
          "apiKey": "N7mB9sK3pC5qL1xZ2fH8vT6wJkG4dY0r"
        }
      },
      "plan": {},
      "edgeFunction": {}
    }
""".trimIndent()

fun mockHTTPClient(settings: String = settingsDefault) {
    mockkConstructor(HTTPClient::class)
    val settingsStream = ByteArrayInputStream(
        settings.toByteArray()
    )
    val httpConnection: HttpURLConnection = mockk()
    val connection = object : Connection(httpConnection, settingsStream, null) {}
    every { anyConstructed<HTTPClient>().settings("cdp.customer.io/v1") } returns connection
    every { anyConstructed<HTTPClient>().settings("cdp-eu.customer.io/v1") } returns connection
}

fun spyStore(scope: TestScope, dispatcher: TestDispatcher): Store {
    val store = spyk(Store())
    every { store getProperty "sovranScope" } propertyType CoroutineScope::class returns scope
    every { store getProperty "syncQueue" } propertyType CoroutineContext::class returns dispatcher
    every { store getProperty "updateQueue" } propertyType CoroutineContext::class returns dispatcher
    return store
}

fun createAnalyticsConfig(
    moduleConfig: DataPipelinesModuleConfig,
    errorHandler: ErrorHandler? = null,
    application: Application? = null
): Configuration {
    val configuration = if (application != null) {
        Configuration(writeKey = moduleConfig.cdpApiKey, application = application)
    } else {
        Configuration(writeKey = moduleConfig.cdpApiKey)
    }

    return configuration.let { config ->
        updateAnalyticsConfig(moduleConfig = moduleConfig).invoke(config)
        config
    }
}

fun createTestAnalyticsInstance(
    moduleConfig: DataPipelinesModuleConfig,
    application: Application? = null,
    testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
    testScope: TestScope = TestScope(testDispatcher)
): Analytics {
    val configuration = if (application != null) {
        createAnalyticsConfig(moduleConfig = moduleConfig, application = application)
    } else {
        createAnalyticsConfig(moduleConfig = moduleConfig).apply {
            this.application = "Test"
        }
    }
    return object : Analytics(configuration, TestCoroutineConfiguration(testDispatcher, testScope)) {}
}

fun Analytics.clearPersistentStorage() {
    val writeKey = configuration.writeKey
    File("/tmp/analytics-kotlin/$writeKey").deleteRecursively()
}
