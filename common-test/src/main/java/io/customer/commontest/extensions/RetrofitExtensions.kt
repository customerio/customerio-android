package io.customer.commontest.extensions

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

fun MockWebServer.enqueueNoInternetConnection() {
    // throws an IOException which is a network error
    // https://github.com/square/okhttp/issues/3533
    enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
}

fun MockWebServer.enqueue(code: Int, responseBody: String?) {
    enqueue(
        MockResponse().apply {
            setResponseCode(code)
            responseBody?.let { setBody(it) }
        }
    )
}

fun MockWebServer.enqueueSuccessful() {
    enqueue(200, null)
}
