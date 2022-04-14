package io.customer.sdk.extensions

import retrofit2.HttpException
import retrofit2.Response

private fun <T> Response<T>.bodyOrThrow(): T {
    if (!isSuccessful) throw HttpException(this)
    return body()!!
}

private fun <T> Response<T>.toException() = HttpException(this)

fun <T> Response<T>.toResultUnit(): Result<Unit> = toResult { }

fun <T> Response<T>.toResult(): Result<T> = toResult { it }

fun <T, E> Response<T>.toResult(mapper: (T) -> E): Result<E> {
    return try {
        if (isSuccessful) {
            Result.success(mapper(bodyOrThrow()))
        } else {
            Result.failure(toException())
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
