package com.safedb.ui

import com.safedb.model.Dialect

internal fun dialectLabel(dialect: Dialect): String = when (dialect) {
    Dialect.Postgres -> "PostgreSQL"
    Dialect.MySql -> "MySQL"
    Dialect.Mssql -> "SQL Server"
    Dialect.Oracle -> "Oracle"
}
