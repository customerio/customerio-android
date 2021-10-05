package io.customer.sdk.api.retrofit

import retrofit2.CallAdapter
import retrofit2.Retrofit
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * A custom Retrofit.CallAdapter that returns adapter using `CustomerIoCall` class
 */
internal class CustomerIoCallAdapter<T : Any>(
    private val responseType: Type
) : CallAdapter<T, CustomerIoCall<T>> {

    override fun adapt(call: retrofit2.Call<T>): CustomerIoCall<T> {
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
        returnType.let {
            return try {
                // get enclosing type
                val enclosingType = (it as ParameterizedType)

                // ensure enclosing type is 'CustomerIoCall'
                if (enclosingType.rawType != CustomerIoCall::class.java)
                    null
                else {
                    val type = enclosingType.actualTypeArguments[0]
                    CustomerIoCallAdapter<Any>(type)
                }
            } catch (ex: ClassCastException) {
                null
            }
        }
    }
}
