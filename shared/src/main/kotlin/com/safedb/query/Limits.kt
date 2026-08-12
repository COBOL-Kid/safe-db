package com.safedb.query

fun parseLimit(raw: Int): Int {
    if (raw < 1) return DEFAULT_LIMIT
    if (raw > MAX_LIMIT) return MAX_LIMIT
    return raw
}

fun parseLimit(raw: String): Int {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return DEFAULT_LIMIT
    val digits = trimmed.takeWhile { it.isDigit() }
    if (digits.isEmpty()) return DEFAULT_LIMIT
    return parseLimit(digits.toInt())
}
