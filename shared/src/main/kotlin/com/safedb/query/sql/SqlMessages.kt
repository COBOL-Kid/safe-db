package com.safedb.query.sql

// All user-facing rejection copy for the SQL screen lives here.
internal object SqlMessages {
    fun notSelect(found: String) =
        "Only SELECT statements can run here — Safe-DB is read-only. (found ${sqlWord(found)})"

    const val CTE = "WITH clauses (CTEs) aren't supported — write a single SELECT."
    const val MULTIPLE_STATEMENTS = "Only one statement can run at a time."
    const val FUNCTIONS =
        "Functions and aggregates (COUNT, SUM, …) aren't supported here. Run the base query, then aggregate in Explore."
    const val SUBQUERY = "Subqueries aren't supported — flatten to joins and filters."
    const val OUTER_JOIN = "Only INNER JOIN with column equality (a.x = b.y) is supported."
    const val JOIN_CONDITION =
        "Join conditions must be column equalities like a.x = b.y — put filters in WHERE."
    const val COLUMN_COMPARE =
        "Comparing two columns isn't supported — compare a column to a value."
    const val COLUMN_ALIAS = "Column aliases aren't supported — columns keep their own names."
    const val EXPRESSION = "Expressions and calculations aren't supported — select plain columns."
    const val HAVING = "HAVING isn't supported. Run the grouped query, then filter in Explore."
    const val SET_OPERATION =
        "UNION, INTERSECT, and EXCEPT aren't supported — run one query at a time."
    const val OFFSET = "OFFSET isn't supported — narrow the query with filters instead."
    const val NOT_CONDITION =
        "NOT before a condition isn't supported — use the negated operator (<>, NOT LIKE, NOT IN)."
    const val COMPARE_NULL = "Use IS NULL or IS NOT NULL to test for null."
    const val STAR_MIX = "* can't be combined with other columns — use table.* or list columns."
    const val LIMIT_WHOLE_NUMBER = "LIMIT must be a whole number."
    const val LIMIT_POSITIVE = "LIMIT must be 1 or more."

    fun topElsewhere(dialect: String) = "TOP is SQL Server syntax — use LIMIT on $dialect."

    fun ilikeElsewhere(dialect: String) = "ILIKE is PostgreSQL syntax — use LIKE on $dialect."

    const val SCHEMA_REQUIRED = "Select a schema, or qualify the table as schema.table."

    // Each ON condition becomes an edge in the builder's join graph, so it must connect the table
    // being joined to one already in the query. Anything else would be silently dropped at compile.
    const val JOIN_EDGE =
        "Each ON condition must link the joined table to a table already in the query — put other conditions in WHERE."

    fun literalTypeMismatch(column: String, dataType: String) =
        "This value doesn't match the type of '$column' ($dataType) — the query would compare something different than written."

    fun dateTimeFormat(column: String) =
        "'$column' needs a date like '2024-01-01' or a timestamp like '2024-01-01 09:30:00'."

    fun dateFormat(column: String) = "'$column' needs a date like '2024-01-01'."

    const val MYSQL_EXEC_COMMENT =
        "MySQL executable comments (/*! … */) aren't supported — write the statement directly."

    const val OPTIMIZER_HINT = "Optimizer hints (/*+ … */) aren't supported."

    const val NATIONAL_STRING =
        "National string literals (N'…') aren't supported — use an ordinary quoted string."

    const val MYSQL_BACKSLASH_AMBIGUOUS =
        "This string's backslash means different things depending on the server's NO_BACKSLASH_ESCAPES mode. Pin sql_mode with the connection's sessionVariables driver property, or rewrite the string without the ambiguous backslash."

    const val PAREN_DEPTH =
        "Conditions are nested too deeply — use at most $MAX_CONDITION_PAREN_DEPTH levels of parentheses."

    fun notOperator(construct: String) =
        "NOT $construct isn't supported — rewrite it with a supported operator."
}
