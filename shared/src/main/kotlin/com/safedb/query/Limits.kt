package com.safedb.query

/** Clamp a user-entered limit to the allowed `[1, MAX_LIMIT]` range. */
fun parseLimit(raw: Int): Int {
    if (raw < 1) return 1
    if (raw > MAX_LIMIT) return MAX_LIMIT
    return raw
}

fun parseLimit(raw: String): Int {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return 1
    val digits = trimmed.takeWhile { it.isDigit() }
    if (digits.isEmpty()) return 1
    return parseLimit(digits.toInt())
}
