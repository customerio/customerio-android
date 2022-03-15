package io.customer.sdk.api.retrofit

import io.customer.base.comunication.Action
import io.customer.base.data.Result
import io.customer.sdk.extensions.getErrorResult
import io.customer.sdk.extensions.toCompatibleResult
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Class to convert retrofit `Call` object to `Action` object
 */
class CustomerIoCall<T : Any>(
    private val call: Call<T>,
) : Action<T> {
    override fun execute(): Result<T> {
        return try {
            call.execute().toCompatibleResult()
        } catch (t: Throwable) {
            t.getErrorResult()
        }
    }

    override fun enqueue(callback: Action.Callback<T>) {
        call.enqueue(
            object : Callback<T> {
                override fun onResponse(call: Call<T>, response: Response<T>) {
                    if (!call.isCanceled) {
                        callback.onResult(response.toCompatibleResult())
                    }
                }

                override fun onFailure(call: Call<T>, t: Throwable) {
                    if (!call.isCanceled) {
                        callback.onResult(t.getErrorResult())
                    }
                }
            }
        )
    }

    override fun cancel() {
        call.cancel()
    }
}
