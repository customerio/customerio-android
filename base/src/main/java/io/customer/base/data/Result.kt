package io.customer.base.data

import io.customer.base.error.ErrorDetail

/**
 * Exists for Java compatibility to return in public facing SDK functions.
 */
sealed class Result<T> {
    open fun get(): T? = null

    fun getOrThrow(): T = when (this) {
        is Success -> get()
        is ErrorResult -> throw error.cause
    }

    val isSuccess: Boolean
        get() = this is Success

    val isError: Boolean
        get() = this is ErrorResult
}

data class Success<T>(val data: T) : Result<T>() {
    override fun get(): T = data
}

data class ErrorResult<T>(val error: ErrorDetail) : Result<T>()
