package com.monster0506.divvy.domain

private const val MILLIS_PER_DAY = 86_400_000L

/** `YYYY-MM-DD` for a calendar pick, which is midnight UTC of the chosen day in milliseconds. */
fun isoDateFromUtcMillis(millis: Long): String {
    val z = millis.floorDiv(MILLIS_PER_DAY) + 719_468
    val era = z.floorDiv(146_097L)
    val dayOfEra = z - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthIndex = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthIndex + 2) / 5 + 1
    val month = if (monthIndex < 10) monthIndex + 3 else monthIndex - 9
    val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
    return "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}

/** The calendar's starting point for a `YYYY-MM-DD` date, or null when it is not one. */
fun utcMillisFromIso(iso: String): Long? {
    val parts = iso.split("-")
    if (parts.size != 3) return null
    val year = parts[0].toLongOrNull() ?: return null
    val month = parts[1].toLongOrNull()?.takeIf { it in 1..12 } ?: return null
    val day = parts[2].toLongOrNull()?.takeIf { it in 1..31 } ?: return null
    val shifted = if (month <= 2) year - 1 else year
    val era = shifted.floorDiv(400L)
    val yearOfEra = shifted - era * 400
    val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return (era * 146_097 + dayOfEra - 719_468) * MILLIS_PER_DAY
}