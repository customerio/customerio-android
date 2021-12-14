package io.customer.sdk.api.retrofit

import io.customer.base.comunication.Action
import retrofit2.Call
import retrofit2.CallAdapter
import retrofit2.Retrofit
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * A custom Retrofit.CallAdapter that returns adapter using `CustomerIoCall` class
 */
internal class CustomerIoCallAdapter<T : Any>(
    private val responseType: Type
) : CallAdapter<T, Action<T>> {

    override fun adapt(call: Call<T>): Action<T> {
        return CustomerIoCall(call)
    }

    override fun responseType(): Type = responseType
}

internal class CustomerIoCallAdapterFactory private constructor() : CallAdapter.Factory() {

    companion object {
        @JvmStatic
        fun create() = CustomerIoCallAdapterFactory()
    }

    override fun get(
        returnType: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit
    ): CallAdapter<*, *>? {
        // ensure enclosing type is 'CustomerIoCall'
        if (getRawType(returnType) != CustomerIoCall::class.java) {
            return null
        }

        if (returnType !is ParameterizedType) {
            throw IllegalArgumentException("Call return type must be parameterized as Call<Foo>")
        }

        val type: Type = getParameterUpperBound(0, returnType)
        return CustomerIoCallAdapter<Any>(type)
    }
}
