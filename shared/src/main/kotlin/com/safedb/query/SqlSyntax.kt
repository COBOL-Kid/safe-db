package com.safedb.query

import com.safedb.model.Dialect

internal fun quote(ident: String, dialect: Dialect): String =
    when (dialect) {
        Dialect.Postgres,
        Dialect.Oracle -> "\"${ident.replace("\"", "\"\"")}\""
        Dialect.MySql -> "`${ident.replace("`", "``")}`"
        Dialect.Mssql -> "[${ident.replace("]", "]]")}]"
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
