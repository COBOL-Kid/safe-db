package com.safedb.query

/** Separator between table alias and column name in selected-column keys. */
const val COLUMN_KEY_SEP = '\u0000'

fun columnKey(alias: String, column: String): String = "$alias$COLUMN_KEY_SEP$column"

fun columnKeyPrefix(alias: String): String = "$alias$COLUMN_KEY_SEP"

fun parseColumnKey(key: String): Pair<String, String> {
    val sep = key.indexOf(COLUMN_KEY_SEP)
    if (sep != -1) {
        return key.substring(0, sep) to key.substring(sep + 1)
    }
    // Legacy keys used a single dot between alias and column.
    val dot = key.indexOf('.')
    if (dot == -1) {
        return key to ""
    }
    return key.substring(0, dot) to key.substring(dot + 1)
}
