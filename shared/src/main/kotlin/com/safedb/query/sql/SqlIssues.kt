package com.safedb.query.sql

import com.safedb.model.QuerySpec

enum class SqlIssueCode {
    NotSelect,
    MultipleStatements,
    Syntax,
    Unsupported,
    UnknownSchema,
    UnknownTable,
    UnknownColumn,
    AmbiguousColumn,
    DuplicateAlias,
    SchemaRequired,
    InvalidLimit,
    LiteralTypeMismatch,
}

data class SqlIssue(val code: SqlIssueCode, val message: String, val span: SqlSpan?)

sealed class SqlParseResult {
    data class Success(val spec: QuerySpec, val notes: List<String>) : SqlParseResult()

    data class Failure(val issues: List<SqlIssue>) : SqlParseResult()
}
