package com.safedb.query.sql

import com.safedb.model.Dialect

private val CORE_KEYWORDS =
    setOf(
        "SELECT",
        "DISTINCT",
        "FROM",
        "AS",
        "INNER",
        "JOIN",
        "ON",
        "WHERE",
        "AND",
        "OR",
        "NOT",
        "LIKE",
        "IN",
        "BETWEEN",
        "IS",
        "NULL",
        "GROUP",
        "ORDER",
        "BY",
        "ASC",
        "DESC",
        "LIMIT",
        "FETCH",
        "FIRST",
        "ROWS",
        "ONLY",
        "TRUE",
        "FALSE",
    )

fun sqlKeywords(dialect: Dialect): Set<String> =
    when (dialect) {
        Dialect.Postgres -> CORE_KEYWORDS + "ILIKE"
        Dialect.MySql -> CORE_KEYWORDS
        Dialect.Mssql -> CORE_KEYWORDS + "TOP"
        Dialect.Oracle -> CORE_KEYWORDS
    }

// First words that identify a non-SELECT statement for the "read-only" rejection.
internal val BLOCKED_STATEMENT_STARTERS =
    setOf(
        "UPDATE",
        "INSERT",
        "DELETE",
        "MERGE",
        "UPSERT",
        "REPLACE",
        "CREATE",
        "DROP",
        "ALTER",
        "TRUNCATE",
        "GRANT",
        "REVOKE",
        "EXEC",
        "EXECUTE",
        "CALL",
        "SET",
        "USE",
        "EXPLAIN",
        "DESCRIBE",
        "DESC",
        "SHOW",
        "BEGIN",
        "COMMIT",
        "ROLLBACK",
        "LOCK",
        "VACUUM",
        "ANALYZE",
        "COPY",
        "DO",
        "PREPARE",
        "DECLARE",
    )
