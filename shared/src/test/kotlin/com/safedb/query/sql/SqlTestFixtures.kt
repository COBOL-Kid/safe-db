package com.safedb.query.sql

import com.safedb.model.ColumnInfo
import com.safedb.model.IndexInfo
import com.safedb.model.Schema
import com.safedb.model.TableInfo
import com.safedb.model.classifyColumn

internal fun column(
    name: String,
    dataType: String,
    indexed: Boolean = false,
    joinEligible: Boolean = false,
): ColumnInfo =
    ColumnInfo(
        name = name,
        dataType = dataType,
        nullable = true,
        isIndexed = indexed,
        joinEligible = joinEligible,
        category = classifyColumn(dataType),
    )

internal fun sqlTestSchema(): Schema =
    Schema(
        tables =
            listOf(
                TableInfo(
                    schema = "public",
                    name = "users",
                    columns =
                        listOf(
                            column("id", "int", indexed = true, joinEligible = true),
                            column("name", "text"),
                            column("email", "text"),
                            column("active", "boolean"),
                            column("created_at", "timestamp"),
                            column("category_id", "int", indexed = true, joinEligible = true),
                        ),
                    indexes =
                        listOf(
                            IndexInfo(
                                name = "users_pkey",
                                columns = listOf("id"),
                                supportsEquality = true,
                                isUnique = true,
                                isPrimary = true,
                            ),
                            IndexInfo(
                                name = "users_category_id_idx",
                                columns = listOf("category_id"),
                                supportsEquality = true,
                            ),
                        ),
                ),
                TableInfo(
                    schema = "public",
                    name = "categories",
                    columns =
                        listOf(
                            column("id", "int", indexed = true, joinEligible = true),
                            column("name", "text"),
                        ),
                    indexes =
                        listOf(
                            IndexInfo(
                                name = "categories_pkey",
                                columns = listOf("id"),
                                supportsEquality = true,
                                isUnique = true,
                                isPrimary = true,
                            )
                        ),
                ),
                TableInfo(
                    schema = "Sales",
                    name = "Invoices",
                    columns =
                        listOf(
                            column("InvoiceId", "int", indexed = true, joinEligible = true),
                            column("Amount", "numeric"),
                        ),
                    indexes =
                        listOf(
                            IndexInfo(
                                name = "invoices_pkey",
                                columns = listOf("InvoiceId"),
                                supportsEquality = true,
                                isUnique = true,
                                isPrimary = true,
                            )
                        ),
                ),
            )
    )
