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

fun <T> Response<T>.toCompatibleResultUnit(): Result<Unit> = toCompatibleResult { }

fun <T> Response<T>.toResultUnit(): kotlin.Result<Unit> = toResult { }

fun <T> Response<T>.toCompatibleResult(): Result<T> = toCompatibleResult { it }

fun <T> Response<T>.toResult(): kotlin.Result<T> = toResult { it }

fun <T, E> Response<T>.toCompatibleResult(mapper: (T) -> E): Result<E> {
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

fun <T, E> Response<T>.toResult(mapper: (T) -> E): kotlin.Result<E> {
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
