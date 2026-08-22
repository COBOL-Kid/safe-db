package com.safedb.query.sql

import com.safedb.model.Dialect
import java.util.Locale

internal fun sqlWord(text: String): String = text.uppercase(Locale.ROOT)

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
        "TRUE",
        "FALSE",
    )

// Words that only ever appear inside FETCH FIRST n ROWS ONLY. Reserving them would make `first`,
// `rows`, and `only` unusable as unquoted columns, which they are not in any supported dialect —
// the parser matches them through wordAt, which accepts identifiers.
private val CLAUSE_WORDS = setOf("FIRST", "ROWS", "ONLY")

fun sqlKeywords(dialect: Dialect): Set<String> =
    when (dialect) {
        Dialect.Postgres -> CORE_KEYWORDS + "ILIKE"
        Dialect.MySql -> CORE_KEYWORDS
        Dialect.Mssql -> CORE_KEYWORDS + "TOP"
        Dialect.Oracle -> CORE_KEYWORDS
    }

// Completion may offer the clause words even though they are not reserved.
fun sqlCompletionKeywords(dialect: Dialect): Set<String> = sqlKeywords(dialect) + CLAUSE_WORDS

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
