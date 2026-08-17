package com.safedb.query

import com.safedb.model.Dialect
import com.safedb.query.sql.sqlKeywords
import com.safedb.query.sql.sqlWord
import java.util.Locale

internal fun quote(ident: String, dialect: Dialect): String =
    when (dialect) {
        Dialect.Postgres,
        Dialect.Oracle -> "\"${ident.replace("\"", "\"\"")}\""
        Dialect.MySql -> "`${ident.replace("`", "``")}`"
        Dialect.Mssql -> "[${ident.replace("]", "]]")}]"
    }

internal fun foldUnquoted(name: String, dialect: Dialect): String =
    when (dialect) {
        Dialect.Postgres -> name.lowercase(Locale.ROOT)
        Dialect.Oracle -> name.uppercase(Locale.ROOT)
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
        Dialect.MySql -> "?"
        Dialect.Mssql -> "@P$idx"
        Dialect.Oracle -> ":$idx"
    }

internal fun buildIlike(columnRef: String, ph: String, dialect: Dialect): String =
    when (dialect) {
        Dialect.Postgres -> "$columnRef ILIKE $ph"
        Dialect.Mssql,
        Dialect.MySql -> "LOWER($columnRef) LIKE LOWER($ph)"
        Dialect.Oracle -> "UPPER($columnRef) LIKE UPPER($ph)"
    }
