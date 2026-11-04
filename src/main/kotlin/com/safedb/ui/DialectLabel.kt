package com.safedb.ui

import com.safedb.model.Dialect
import com.safedb.model.displayName

internal fun dialectLabel(dialect: Dialect): String = dialect.displayName()
