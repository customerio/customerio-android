package io.customer.common_test.extensions

import io.customer.sdk.util.JsonAdapter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody

fun <F : Any> F.toResponseBody(jsonAdapter: JsonAdapter): ResponseBody {
    return jsonAdapter.toJson(this).toResponseBody("application/json".toMediaType())
}
