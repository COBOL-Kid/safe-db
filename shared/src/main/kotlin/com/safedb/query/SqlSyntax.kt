package com.safedb.query

import com.safedb.model.Dialect
import com.safedb.model.FilterOp
import com.safedb.query.sql.sqlKeywords
import com.safedb.query.sql.sqlWord
import java.util.Locale

internal fun quote(ident: String, dialect: Dialect): String =
    when (dialect) {
        Dialect.Postgres,
        Dialect.Oracle,
        Dialect.Db2 -> "\"${ident.replace("\"", "\"\"")}\""
        Dialect.MySql -> "`${ident.replace("`", "``")}`"
        Dialect.Mssql -> "[${ident.replace("]", "]]")}]"
    }

internal fun foldUnquoted(name: String, dialect: Dialect): String =
    when (dialect) {
        Dialect.Postgres -> name.lowercase(Locale.ROOT)
        Dialect.Oracle,
        Dialect.Db2 -> name.uppercase(Locale.ROOT)
        Dialect.MySql,
        Dialect.Mssql -> name
    }

internal fun quoteIfRequired(name: String, dialect: Dialect): String {
    val tokenizerSafe =
        name.isNotEmpty() &&
            (name[0].isLetter() || name[0] == '_') &&
            name.all { it.isLetterOrDigit() || it == '_' || it == '$' }
    return if (
        !tokenizerSafe ||
            sqlWord(name) in sqlKeywords(dialect) ||
            foldUnquoted(name, dialect) != name
    ) {
        quote(name, dialect)
    } else {
        name
    }
}

internal fun placeholder(idx: Int, dialect: Dialect): String =
    when (dialect) {
        Dialect.Postgres -> "$$idx"
        Dialect.MySql,
        Dialect.Db2 -> "?"
        Dialect.Mssql -> "@P$idx"
        Dialect.Oracle -> ":$idx"
    }

internal fun buildIlike(columnRef: String, ph: String, dialect: Dialect): String =
    when (dialect) {
        Dialect.Postgres -> "$columnRef ILIKE $ph"
        Dialect.Mssql,
        Dialect.MySql -> "LOWER($columnRef) LIKE LOWER($ph)"
        Dialect.Oracle,
        Dialect.Db2 -> "UPPER($columnRef) LIKE UPPER($ph)"
    }

// Escapes the LIKE metacharacters so a friendly pattern matches them literally. Kept in sync with
// the ESCAPE '!' the renderers append.
internal fun escapeLikeLiteral(text: String): String =
    text.replace("!", "!!").replace("%", "!%").replace("_", "!_")

internal fun FilterOp.isFriendlyTextPattern(): Boolean =
    this in
        setOf(
            FilterOp.Contains,
            FilterOp.ContainsIgnoreCase,
            FilterOp.NotContains,
            FilterOp.StartsWith,
            FilterOp.EndsWith,
        )

// The LIKE operator a friendly text pattern uses. ContainsIgnoreCase is resolved by the caller
// because Postgres supports ILIKE while other dialects fall back to LIKE.
internal fun friendlyPatternOperator(op: FilterOp): String =
    when (op) {
        FilterOp.NotContains -> "NOT LIKE"
        FilterOp.Contains,
        FilterOp.StartsWith,
        FilterOp.EndsWith -> "LIKE"
        else -> error("Not a friendly text pattern: $op")
    }

// Wraps an escaped literal with the wildcards a friendly text operator implies.
internal fun friendlyPatternText(op: FilterOp, text: String): String {
    val escaped = escapeLikeLiteral(text)
    return when (op) {
        FilterOp.Contains,
        FilterOp.ContainsIgnoreCase,
        FilterOp.NotContains -> "%$escaped%"
        FilterOp.StartsWith -> "$escaped%"
        FilterOp.EndsWith -> "%$escaped"
        else -> error("Not a friendly text pattern: $op")
    }
}
