package com.monster0506.divvy.server.domain

/**
 * What someone typed in the receipt list's store search, and which store names it finds.
 *
 * - Case, spaces and punctuation are ignored, so `trader joes` finds "Trader Joe's" and `cost co` finds "COSTCO".
 * - The search can be any part of the name: `whole` finds "COSTCO WHOLESALE".
 * - `*` stands for any run of characters: `cost*sale` finds "COSTCO WHOLESALE".
 * - Without a `*`, a search of [MIN_FUZZY_LENGTH] or more letters and digits also allows a typo: one for
 *   up to [TWO_TYPOS_LENGTH] - 1 characters, two from then on. A typo is a wrong, missing, extra or swapped
 *   character, so `walmrt`, `cosco` and `tagret` find "Walmart", "Costco" and "Target".
 * - A search with no letters or digits at all (`%`) is looked for as typed, ignoring case.
 */
class StoreSearch(typed: String) {
    private val literal = typed.trim().lowercase()
    /** The letters and digits between `*`s, in order. */
    private val pieces = typed.split('*').map(::letters).filter { it.isNotEmpty() }
    private val wildcard = '*' in typed
    private val typos = when {
        wildcard || pieces.size != 1 -> 0
        pieces[0].length >= TWO_TYPOS_LENGTH -> 2
        pieces[0].length >= MIN_FUZZY_LENGTH -> 1
        else -> 0
    }

    fun matches(name: String): Boolean {
        if (pieces.isEmpty()) return if (wildcard) true else name.lowercase().contains(literal)
        val target = letters(name)
        if (inOrder(target)) return true
        return typos > 0 && closestDistance(pieces[0], target) <= typos
    }

    /** Every piece appears in [target], each after the one before. */
    private fun inOrder(target: String): Boolean {
        var from = 0
        for (piece in pieces) {
            val at = target.indexOf(piece, from)
            if (at < 0) return false
            from = at + piece.length
        }
        return true
    }

    private companion object {
        const val MIN_FUZZY_LENGTH = 4
        const val TWO_TYPOS_LENGTH = 8

        fun letters(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

        /**
         * The fewest typos (wrong, missing, extra or two swapped characters) that turn [query] into some
         * part of [target]: an edit distance where the part may start and end anywhere.
         */
        fun closestDistance(query: String, target: String): Int {
            val rows = query.length + 1
            val cols = target.length + 1
            // d[i][j]: fewest typos between the first i characters of query and some part of target ending at j.
            val d = Array(rows) { i -> IntArray(cols) { if (it == 0) i else 0 } }
            for (i in 1 until rows) {
                for (j in 1 until cols) {
                    val same = query[i - 1] == target[j - 1]
                    var best = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (same) 0 else 1)
                    if (i > 1 && j > 1 && query[i - 1] == target[j - 2] && query[i - 2] == target[j - 1]) {
                        best = minOf(best, d[i - 2][j - 2] + 1)
                    }
                    d[i][j] = best
                }
            }
            return d[query.length].min()
        }
    }
}
