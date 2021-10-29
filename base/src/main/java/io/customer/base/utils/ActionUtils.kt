package io.customer.base.utils

import io.customer.base.comunication.Action
import io.customer.base.data.ErrorResult
import io.customer.base.data.Result
import io.customer.base.data.Success
import io.customer.base.error.ErrorDetail
import io.customer.base.error.StatusCode

abstract class ActionUtils {
    companion object {
        private fun <T> unIdentifiedUserErrorResult(): ErrorResult<T> {
            return ErrorResult(
                error = ErrorDetail(
                    statusCode = StatusCode.UnIdentifiedUser
                )
            )
        }

        private fun <T : Any> getErrorAction(errorResult: ErrorResult<T>): Action<T> {
            return object : Action<T> {
                override fun execute(): Result<T> = errorResult

                override fun enqueue(callback: Action.Callback<T>) {
                    callback.onResult(errorResult)
                }

                override fun cancel() {}
            }
        }

        fun <T : Any> getUnidentifiedUserAction(): Action<T> {
            return getErrorAction(unIdentifiedUserErrorResult())
        }

        fun getEmptyAction(): Action<Unit> {
            return object : Action<Unit> {
                override fun execute(): Result<Unit> = Success(data = Unit)

                override fun enqueue(callback: Action.Callback<Unit>) {
                    callback.onResult(Success(data = Unit))
                }

                override fun cancel() {}
            }
        }
    }

}
