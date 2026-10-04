package com.monster0506.divvy.domain

/**
 * Reads a date a person typed as `YYYY-MM-DD`, `M/D/YYYY` or `M/D/YY` (20YY) and returns it as `YYYY-MM-DD`,
 * or null if it is not a real calendar date.
 */
fun parseDateInput(input: String): String? {
    val text = input.trim()
    val iso = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""").matchEntire(text)
    val us = Regex("""(\d{1,2})/(\d{1,2})/(\d{2}|\d{4})""").matchEntire(text)
    val (year, month, day) = when {
        iso != null -> Triple(iso.groupValues[1].toInt(), iso.groupValues[2].toInt(), iso.groupValues[3].toInt())
        us != null -> {
            val y = us.groupValues[3]
            Triple(if (y.length == 2) 2000 + y.toInt() else y.toInt(), us.groupValues[1].toInt(), us.groupValues[2].toInt())
        }
        else -> return null
    }
    if (year !in MIN_YEAR..MAX_YEAR || month !in 1..12 || day !in 1..daysIn(year, month)) return null
    return "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}

private const val MIN_YEAR = 2000
private const val MAX_YEAR = 2100

private fun daysIn(year: Int, month: Int): Int = when (month) {
    2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}
