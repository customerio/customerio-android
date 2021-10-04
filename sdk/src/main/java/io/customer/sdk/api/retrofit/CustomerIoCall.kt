package io.customer.sdk.api.retrofit

import io.customer.base.comunication.Action
import io.customer.base.data.ErrorResult
import io.customer.base.data.Result
import io.customer.base.error.ErrorDetail
import io.customer.sdk.extensions.toResult
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class CustomerIoCall<T : Any>(
    private val call: Call<T>,
) : Action<T> {
    override fun execute(): Result<T> {
        return try {
            call.execute().toResult()
        } catch (t: Throwable) {
            ErrorResult(ErrorDetail(cause = t))
        }
    }

    override fun enqueue(callback: Action.Callback<T>) {
        call.enqueue(
            object : Callback<T> {
                override fun onResponse(call: Call<T>, response: Response<T>) {
                    if (!call.isCanceled) {
                        callback.onResult(response.toResult())
                    }
                }

                override fun onFailure(call: Call<T>, t: Throwable) {
                    if (!call.isCanceled) {
                        callback.onResult(ErrorResult(ErrorDetail(cause = t)))
                    }
                }
            }
        )
    }

    override fun cancel() {
        call.cancel()
    }
}
