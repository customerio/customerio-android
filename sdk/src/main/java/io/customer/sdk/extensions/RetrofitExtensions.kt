package io.customer.sdk.extensions

import io.customer.base.data.ErrorResult
import io.customer.base.data.Result
import io.customer.base.data.Success
import io.customer.base.error.ErrorDetail
import retrofit2.HttpException
import retrofit2.Response

private fun <T> Response<T>.bodyOrThrow(): T {
    if (!isSuccessful) throw HttpException(this)
    return body()!!
}

private fun <T> Response<T>.toException() = HttpException(this)

fun <T> Response<T>.toResultUnit(): Result<Unit> = toResult { }

fun <T> Response<T>.toKResultUnit(): kotlin.Result<Unit> = toKResult { }

fun <T> Response<T>.toResult(): Result<T> = toResult { it }

fun <T> Response<T>.toKResult(): kotlin.Result<T> = toKResult { it }

fun <T, E> Response<T>.toResult(mapper: (T) -> E): Result<E> {
    return try {
        if (isSuccessful) {
            Success(data = mapper(bodyOrThrow()))
        } else {
            ErrorResult(toException().getErrorDetail())
        }
    } catch (e: Exception) {
        ErrorResult(ErrorDetail(cause = e))
    }
}

fun <T, E> Response<T>.toKResult(mapper: (T) -> E): kotlin.Result<E> {
    return try {
        if (isSuccessful) {
            kotlin.Result.success(mapper(bodyOrThrow()))
        } else {
            kotlin.Result.failure(toException())
        }
    } catch (e: Exception) {
        kotlin.Result.failure(e)
    }
}
