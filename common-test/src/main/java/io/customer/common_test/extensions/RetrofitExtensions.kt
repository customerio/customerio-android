package io.customer.common_test.extensions

import io.customer.sdk.util.JsonAdapter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

fun <F : Any> F.toResponseBody(jsonAdapter: JsonAdapter): ResponseBody {
    return jsonAdapter.toJson(this).toResponseBody("application/json".toMediaType())
}

fun MockWebServer.enqueueNoInternetConnection() {
    // throws an IOException which is a network error
    // https://github.com/square/okhttp/issues/3533
    enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
}
