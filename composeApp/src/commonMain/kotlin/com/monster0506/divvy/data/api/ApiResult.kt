package com.monster0506.divvy.data.api

import com.monster0506.divvy.data.dto.ApiError

/** What a failed call means to the user, independent of the HTTP details. */
sealed interface ApiFailure {
    data object Offline : ApiFailure
    data object Unauthorised : ApiFailure
    data class Validation(val error: ApiError) : ApiFailure
    data class ServerError(val status: Int?) : ApiFailure
}

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val failure: ApiFailure) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(value))
    is ApiResult.Failure -> this
}

fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Success)?.value
