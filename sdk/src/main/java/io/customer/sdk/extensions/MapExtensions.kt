package io.customer.sdk.extensions

import android.app.Application
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOShared
import io.customer.sdk.SharedWrapperKeys

@Throws(IllegalArgumentException::class)
inline fun <reified T> Map<String, Any?>.getPropertyUnsafe(key: String): T {
    val property = get(key)

    if (property !is T) {
        throw IllegalArgumentException(
            "Invalid value provided for key: $key, value $property must be of type ${T::class.java.simpleName}"
        )
    }
    return property
}

inline fun <reified T> Map<String, Any?>.getProperty(key: String): T? = try {
    getPropertyUnsafe(key)
} catch (ex: IllegalArgumentException) {
    CustomerIOShared.instance().diStaticGraph.logger.error(
        ex.message ?: "getProperty($key) -> IllegalArgumentException"
    )
    null
}

@Throws(IllegalArgumentException::class)
fun Map<String, Any?>.getString(key: String): String = try {
    getPropertyUnsafe<String>(key).takeIfNotBlank() ?: throw IllegalArgumentException(
        "Invalid value provided for $key, must not be blank"
    )
} catch (ex: IllegalArgumentException) {
    CustomerIOShared.instance().diStaticGraph.logger.error(
        ex.message ?: "getString($key) -> IllegalArgumentException"
    )
    throw ex
}

fun Map<String, Any?>.getCustomerIOBuilder(application: Application): CustomerIO.Builder {
    val config = this
    val siteId = config.getString(SharedWrapperKeys.Environment.SITE_ID)
    val apiKey = config.getString(SharedWrapperKeys.Environment.API_KEY)
    val region = config.getProperty<String>(
        SharedWrapperKeys.Environment.REGION
    )?.takeIfNotBlank().toRegion()

    return CustomerIO.Builder(
        siteId = siteId,
        apiKey = apiKey,
        region = region,
        appContext = application
    ).apply {
        setupConfig(config)
    }
}
