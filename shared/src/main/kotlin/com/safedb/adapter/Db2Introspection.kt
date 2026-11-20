package com.safedb.adapter

import com.safedb.model.DB2_SYSTEM_SCHEMAS
import com.safedb.model.EvidenceConfidence
import com.safedb.model.IndexCapabilities
import com.safedb.model.Schema
import com.safedb.model.SortDirection
import java.sql.Connection
import java.sql.ResultSet

// Same names as DB2_SYSTEM_SCHEMAS / BLOCKED_SCHEMAS. Unquoted SQL identifiers fold to
// uppercase on both LUW and z/OS, so the filter compares UPPER(schema).
private val db2ExcludedSchemas =
    DB2_SYSTEM_SCHEMAS.joinToString(prefix = "('", postfix = "')", separator = "','")

internal fun Db2Adapter.introspectSyscat(conn: Connection): Schema {
    val tables =
        conn.metadataRows(
            "SELECT TABSCHEMA, TABNAME FROM SYSCAT.TABLES WHERE TYPE = 'T' " +
                "AND UPPER(TABSCHEMA) NOT IN $db2ExcludedSchemas ORDER BY TABSCHEMA, TABNAME",
        ) { rs ->
            MetadataTableKey(readString(rs, "TABSCHEMA"), readString(rs, "TABNAME"))
        }
    val columns =
        conn.metadataRows(
            "SELECT TABSCHEMA, TABNAME, COLNAME, TYPENAME, NULLS FROM SYSCAT.COLUMNS " +
                "WHERE UPPER(TABSCHEMA) NOT IN $db2ExcludedSchemas " +
                "ORDER BY TABSCHEMA, TABNAME, COLNO",
        ) { rs ->
            columnRow(
                rs,
                schemaLabel = "TABSCHEMA",
                tableLabel = "TABNAME",
                columnLabel = "COLNAME",
                typeLabel = "TYPENAME",
                nullableLabel = "NULLS",
                nullableValue = "Y",
            )
        }
    val indexes =
        conn.metadataRows(
            """
            SELECT I.TABSCHEMA, I.TABNAME, I.INDNAME, C.COLNAME, I.UNIQUERULE, C.COLORDER,
                   I.INDEXTYPE, C.VIRTUAL
            FROM SYSCAT.INDEXES I
            JOIN SYSCAT.INDEXCOLUSE C ON C.INDSCHEMA = I.INDSCHEMA AND C.INDNAME = I.INDNAME
            WHERE UPPER(I.TABSCHEMA) NOT IN $db2ExcludedSchemas
            ORDER BY I.TABSCHEMA, I.TABNAME, I.INDNAME, C.COLSEQ
            """
                .trimIndent(),
        ) { rs ->
            db2Index(
                readString(rs, "TABSCHEMA"),
                readString(rs, "TABNAME"),
                readString(rs, "INDNAME"),
                rs.getString("COLNAME"),
                readString(rs, "UNIQUERULE"),
                rs.getString("COLORDER"),
                indexType = rs.getString("INDEXTYPE"),
                virtual = rs.getString("VIRTUAL")?.trim().equals("Y", ignoreCase = true),
            )
        }
    val foreignKeys =
        completeDb2ForeignKeys(
            conn
                .metadataRows(
                    """
                    SELECT R.TABSCHEMA AS table_schema, R.TABNAME AS table_name,
                           R.CONSTNAME AS constraint_name, CK.COLNAME AS column_name,
                           R.REFTABSCHEMA AS referenced_schema, R.REFTABNAME AS referenced_table,
                           PK.COLNAME AS referenced_column, R.COLCOUNT AS column_count
                    FROM SYSCAT.REFERENCES R
                    JOIN SYSCAT.KEYCOLUSE CK
                      ON CK.TABSCHEMA = R.TABSCHEMA AND CK.TABNAME = R.TABNAME
                     AND CK.CONSTNAME = R.CONSTNAME
                    JOIN SYSCAT.KEYCOLUSE PK
                      ON PK.TABSCHEMA = R.REFTABSCHEMA AND PK.TABNAME = R.REFTABNAME
                     AND PK.CONSTNAME = R.REFKEYNAME AND PK.COLSEQ = CK.COLSEQ
                    WHERE UPPER(R.TABSCHEMA) NOT IN $db2ExcludedSchemas
                    ORDER BY R.TABSCHEMA, R.TABNAME, R.CONSTNAME, CK.COLSEQ
                    """
                        .trimIndent(),
                ) { rs ->
                    countedForeignKey(rs)
                }
                .filterNotNull(),
        )
    val tableSizes =
        conn.tableSizes(
            "SELECT TABSCHEMA, TABNAME, CARD FROM SYSCAT.TABLES " +
                "WHERE UPPER(TABSCHEMA) NOT IN $db2ExcludedSchemas",
            rowEstimateLabel = "CARD",
            confidence = EvidenceConfidence.Low,
            schemaLabel = "TABSCHEMA",
            tableLabel = "TABNAME",
        )
    return assembleSchema(tables, columns, indexes, foreignKeys, tableSizes)
}

internal fun Db2Adapter.introspectSysibm(conn: Connection): Schema {
    val tables =
        conn.metadataRows(
            "SELECT CREATOR, NAME FROM SYSIBM.SYSTABLES WHERE TYPE = 'T' " +
                "AND UPPER(CREATOR) NOT IN $db2ExcludedSchemas ORDER BY CREATOR, NAME",
        ) { rs ->
            MetadataTableKey(readString(rs, "CREATOR"), readString(rs, "NAME"))
        }
    val columns =
        conn.metadataRows(
            "SELECT TBCREATOR, TBNAME, NAME, COLTYPE, NULLS FROM SYSIBM.SYSCOLUMNS " +
                "WHERE UPPER(TBCREATOR) NOT IN $db2ExcludedSchemas " +
                "ORDER BY TBCREATOR, TBNAME, COLNO",
        ) { rs ->
            columnRow(
                rs,
                schemaLabel = "TBCREATOR",
                tableLabel = "TBNAME",
                columnLabel = "NAME",
                typeLabel = "COLTYPE",
                nullableLabel = "NULLS",
                nullableValue = "Y",
            )
        }
    val indexes =
        conn.metadataRows(
            """
            SELECT I.TBCREATOR, I.TBNAME, I.NAME, K.COLNAME, I.UNIQUERULE, K.ORDERING
            FROM SYSIBM.SYSINDEXES I
            JOIN SYSIBM.SYSKEYS K ON K.IXCREATOR = I.CREATOR AND K.IXNAME = I.NAME
            WHERE UPPER(I.TBCREATOR) NOT IN $db2ExcludedSchemas
            ORDER BY I.TBCREATOR, I.TBNAME, I.NAME, K.COLSEQ
            """
                .trimIndent(),
        ) { rs ->
            db2Index(
                readString(rs, "TBCREATOR"),
                readString(rs, "TBNAME"),
                readString(rs, "NAME"),
                rs.getString("COLNAME"),
                readString(rs, "UNIQUERULE"),
                rs.getString("ORDERING"),
                blankOrderMeansNotAKey = true,
            )
        }
    val foreignKeys =
        completeDb2ForeignKeys(
            conn
                .metadataRows(
                    """
                    SELECT R.CREATOR AS table_schema, R.TBNAME AS table_name,
                           R.RELNAME AS constraint_name, F.COLNAME AS column_name,
                           R.REFTBCREATOR AS referenced_schema, R.REFTBNAME AS referenced_table,
                           CASE WHEN TRIM(R.IXNAME) = '' THEN PK.COLNAME ELSE UK.COLNAME END
                               AS referenced_column,
                           R.COLCOUNT AS column_count
                    FROM SYSIBM.SYSRELS R
                    JOIN SYSIBM.SYSFOREIGNKEYS F
                      ON F.CREATOR = R.CREATOR AND F.TBNAME = R.TBNAME AND F.RELNAME = R.RELNAME
                    LEFT JOIN SYSIBM.SYSINDEXES PI
                      ON TRIM(R.IXNAME) = ''
                     AND PI.TBCREATOR = R.REFTBCREATOR AND PI.TBNAME = R.REFTBNAME
                     AND PI.UNIQUERULE = 'P'
                    LEFT JOIN SYSIBM.SYSKEYS PK
                      ON PK.IXCREATOR = PI.CREATOR AND PK.IXNAME = PI.NAME AND PK.COLSEQ = F.COLSEQ
                    LEFT JOIN SYSIBM.SYSKEYS UK
                      ON TRIM(R.IXNAME) <> ''
                     AND UK.IXCREATOR = R.IXOWNER AND UK.IXNAME = R.IXNAME
                     AND UK.COLSEQ = F.COLSEQ
                    WHERE UPPER(R.CREATOR) NOT IN $db2ExcludedSchemas
                    ORDER BY R.CREATOR, R.TBNAME, R.RELNAME, F.COLSEQ
                    """
                        .trimIndent(),
                ) { rs ->
                    countedForeignKey(rs)
                }
                .filterNotNull(),
        )
    val tableSizes =
        conn.tableSizes(
            "SELECT CREATOR, NAME, CARDF FROM SYSIBM.SYSTABLES " +
                "WHERE UPPER(CREATOR) NOT IN $db2ExcludedSchemas",
            rowEstimateLabel = "CARDF",
            confidence = EvidenceConfidence.Low,
            schemaLabel = "CREATOR",
            tableLabel = "NAME",
        )
    return assembleSchema(tables, columns, indexes, foreignKeys, tableSizes)
}

internal fun Db2Adapter.db2Index(
    schema: String,
    table: String,
    name: String,
    column: String?,
    uniqueRule: String,
    ordering: String?,
    indexType: String? = null,
    virtual: Boolean = false,
    blankOrderMeansNotAKey: Boolean = false,
): MetadataIndex {
    val order = ordering?.trim()?.uppercase().orEmpty()
    val kind = indexType?.trim()?.uppercase().orEmpty().ifEmpty { "REG" }
    val included = order == "I" || (blankOrderMeansNotAKey && order.isEmpty())
    val text = kind in setOf("TEXT", "XPTH", "XRGN", "XVIL")
    val structured = kind == "REG" || kind == "CLUS"
    return MetadataIndex(
        MetadataTableKey(schema, table),
        name,
        column,
        kind,
        supportsEquality = !text,
        unique = uniqueRule == "U" || uniqueRule == "P",
        primary = uniqueRule == "P",
        direction =
            when (order) {
                "A" -> SortDirection.Asc
                "D" -> SortDirection.Desc
                else -> null
            },
        included = included,
        capabilities =
            IndexCapabilities(
                equality = !text,
                ordering = structured && (order == "A" || order == "D"),
                specializedText = text,
                expressionKeys = virtual,
                partialPredicate = false,
                includedColumns = true,
            ),
        partial = false,
        expression = virtual || column == null,
    )
}

internal data class CountedForeignKey(val key: MetadataForeignKey, val columnCount: Int)

internal fun countedForeignKey(rs: ResultSet): CountedForeignKey? {
    val columnCount = rs.getObject("column_count") as? Number ?: return null
    return CountedForeignKey(foreignKeyRow(rs), columnCount.toInt())
}

// A partial parent match must not become a shorter key.
internal fun Db2Adapter.completeDb2ForeignKeys(
    rows: List<CountedForeignKey>,
): List<MetadataForeignKey> =
    rows
        .groupBy { row ->
            Triple(
                row.key.table,
                row.key.name,
                row.key.referencedSchema to row.key.referencedTable,
            )
        }
        .values
        .flatMap { group ->
            val columnCount = group.map { it.columnCount }.distinct().singleOrNull()
            if (
                columnCount == null ||
                    group.size != columnCount ||
                    group.any { it.key.referencedColumn.isBlank() }
            ) {
                emptyList()
            } else {
                group.map { it.key }
            }
        }
