package com.monster0506.divvy.platform

import androidx.compose.runtime.Composable
import io.ktor.http.encodeURLParameter

/** Opens [url] with the system handler; [onFailure] runs if nothing can handle it. */
class UrlOpener internal constructor(private val opener: (String, () -> Unit) -> Unit) {
    fun open(url: String, onFailure: () -> Unit = {}) = opener(url, onFailure)
}

@Composable
expect fun rememberUrlOpener(): UrlOpener

private fun formatAmount(amountCents: Long): String {
    require(amountCents > 0) { "Amount must be positive" }
    return "${amountCents / 100}.${(amountCents % 100).toString().padStart(2, '0')}"
}

private fun query(recipient: String?, amountCents: Long, note: String): String = buildList {
    add("txn=pay")
    if (recipient != null) add("recipients=${recipient.encodeURLParameter()}")
    add("amount=${formatAmount(amountCents)}")
    add("note=${note.encodeURLParameter()}")
}.joinToString("&")

private fun cleanUsername(username: String) = normalizeVenmoUsername(username).also {
    require(it.isNotEmpty()) { "Venmo username must not be blank" }
}

private val venmoUsernamePattern = Regex("^[A-Za-z0-9_-]{5,30}$")

/** [raw] without spaces or a leading "@", the form the server stores. */
fun normalizeVenmoUsername(raw: String): String = raw.trim().removePrefix("@")

/** Why [raw] is not a Venmo username, or null if it is one (or blank, which removes it). Mirrors the server. */
fun venmoUsernameProblem(raw: String): String? {
    val username = normalizeVenmoUsername(raw)
    return when {
        username.isEmpty() || venmoUsernamePattern.matches(username) -> null
        username.length < 5 -> "Venmo usernames are at least 5 characters."
        username.length > 30 -> "Venmo usernames are at most 30 characters."
        else -> "Use only letters, numbers, - and _."
    }
}

/** Link that opens the Venmo app on a pre-filled payment. */
fun venmoDeepLink(username: String, amountCents: Long, note: String): String =
    "venmo://paycharge?" + query(cleanUsername(username), amountCents, note)

/** Browser link used when the Venmo app is not installed. */
fun venmoWebUrl(username: String, amountCents: Long, note: String): String =
    "https://venmo.com/${cleanUsername(username).encodeURLParameter()}?" + query(null, amountCents, note)

/** Opens Venmo pre-filled with recipient, amount and note, falling back to the web page. */
fun UrlOpener.payWithVenmo(username: String, amountCents: Long, note: String) {
    open(venmoDeepLink(username, amountCents, note)) {
        open(venmoWebUrl(username, amountCents, note))
    }
}
