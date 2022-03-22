package io.customer.common_test

import io.customer.sdk.api.retrofit.CustomerIoCall
import okhttp3.Request
import okio.Timeout
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MockRetrofitSuccess<T : Any>(private val result: T) : Call<T> {

    fun toCustomerIoCall(): CustomerIoCall<T> {
        return CustomerIoCall(
            call = this
        )
    }

    override fun enqueue(callback: Callback<T>) {
        callback.onResponse(this, execute())
    }

    override fun isExecuted(): Boolean {
        return true
    }

    override fun clone(): Call<T> {
        return this
    }

    override fun isCanceled(): Boolean {
        return false
    }

    override fun cancel() {
    }

    override fun execute(): Response<T> {
        return Response.success(result)
    }

    override fun request(): Request {
        return null!!
    }

    override fun timeout(): Timeout {
        return Timeout()
    }
}
