package com.monster0506.divvy.data.api

/**
 * The one wording screens use when the server can't be reached. The app-wide connection banner
 * explains what to check, so screens only say what didn't happen.
 */
const val OFFLINE_MESSAGE = "Could not reach the server."

/** A message a user can read for any failed API call. */
internal fun ApiFailure.userMessage(): String = when (this) {
    ApiFailure.Offline -> OFFLINE_MESSAGE
    ApiFailure.Unauthorised -> "The server rejected the API key. Check Account > Server."
    is ApiFailure.Validation -> error.message.ifBlank { "The server did not accept that change." }
    is ApiFailure.ServerError -> "Something went wrong on our side. Please try again."
}

internal fun Throwable.apiMessage(): String =
    if (this is ApiException) failure.userMessage() else "Something went wrong. Please try again."