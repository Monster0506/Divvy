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

private fun cleanUsername(username: String) = username.trim().removePrefix("@").also {
    require(it.isNotEmpty()) { "Venmo username must not be blank" }
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
