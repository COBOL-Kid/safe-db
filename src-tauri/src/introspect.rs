use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Schema {
    pub tables: Vec<TableInfo>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TableInfo {
    pub schema: String,
    pub name: String,
    pub columns: Vec<ColumnInfo>,
    pub indexes: Vec<IndexInfo>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ColumnInfo {
    pub name: String,
    pub data_type: String,
    pub nullable: bool,
    pub is_indexed: bool,
    #[serde(default)]
    pub join_eligible: bool,
    #[serde(default)]
    pub category: ColumnCategory,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct IndexInfo {
    pub name: String,
    pub columns: Vec<String>,
    #[serde(default)]
    pub included_columns: Vec<String>,
    #[serde(default)]
    pub kind: String,
    #[serde(default = "default_true")]
    pub supports_equality: bool,
    pub is_unique: bool,
    pub is_primary: bool,
}

fn default_true() -> bool {
    true
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
pub enum ColumnCategory {
    Text,
    Integer,
    Decimal,
    Bool,
    Date,
    DateTime,
    Binary,
    Json,
    #[default]
    Other,
}

pub fn classify_column(data_type: &str) -> ColumnCategory {
    let dt = data_type.to_ascii_lowercase();
    if matches!(dt.as_str(), "bool" | "boolean" | "bit") {
        ColumnCategory::Bool
    } else if dt == "date" {
        ColumnCategory::Date
    } else if dt.starts_with("timestamp")
        || dt.starts_with("datetime")
        || matches!(dt.as_str(), "smalldatetime" | "time")
    {
        ColumnCategory::DateTime
    } else if matches!(
        dt.as_str(),
        "int"
            | "integer"
            | "smallint"
            | "bigint"
            | "mediumint"
            | "tinyint"
            | "serial"
            | "bigserial"
    ) {
        ColumnCategory::Integer
    } else if matches!(
        dt.as_str(),
        "decimal"
            | "numeric"
            | "number"
            | "real"
            | "double"
            | "float"
            | "float4"
            | "float8"
            | "money"
            | "smallmoney"
            | "double precision"
    ) || dt.starts_with("decimal")
        || dt.starts_with("numeric")
        || dt.starts_with("number")
    {
        ColumnCategory::Decimal
    } else if dt.contains("binary") || dt.contains("blob") || matches!(dt.as_str(), "bytea" | "raw")
    {
        ColumnCategory::Binary
    } else if matches!(dt.as_str(), "json" | "jsonb") {
        ColumnCategory::Json
    } else if matches!(
        dt.as_str(),
        "text"
            | "varchar"
            | "char"
            | "character"
            | "character varying"
            | "string"
            | "tinytext"
            | "mediumtext"
            | "longtext"
            | "nvarchar"
            | "nchar"
            | "varchar2"
            | "nvarchar2"
            | "clob"
            | "nclob"
            | "xml"
            | "uuid"
    ) || dt.starts_with("varchar")
        || dt.starts_with("char")
        || dt.starts_with("nchar")
        || dt.starts_with("nvarchar")
    {
        ColumnCategory::Text
    } else {
        ColumnCategory::Other
    }
}

/// Mark which columns appear in at least one index, mutating `columns` in place.
pub fn mark_indexed_columns(columns: &mut [ColumnInfo], indexes: &[IndexInfo]) {
    let indexed: std::collections::HashSet<&str> = indexes
        .iter()
        .flat_map(|idx| idx.columns.iter().map(String::as_str))
        .collect();
    for col in columns.iter_mut() {
        col.is_indexed = indexed.contains(col.name.as_str());
        col.join_eligible = indexes.iter().any(|index| {
            index.supports_equality
                && index
                    .columns
                    .first()
                    .is_some_and(|first| first == &col.name)
        });
        col.category = classify_column(&col.data_type);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn marks_primary_composite_and_non_indexed_columns() {
        let mut columns = vec![
            ColumnInfo {
                name: "id".into(),
                data_type: "int".into(),
                nullable: false,
                is_indexed: false,
                ..ColumnInfo::default()
            },
            ColumnInfo {
                name: "category_id".into(),
                data_type: "int".into(),
                nullable: false,
                is_indexed: false,
                ..ColumnInfo::default()
            },
            ColumnInfo {
                name: "name".into(),
                data_type: "text".into(),
                nullable: true,
                is_indexed: false,
                ..ColumnInfo::default()
            },
        ];
        let indexes = vec![
            IndexInfo {
                name: "pk".into(),
                columns: vec!["id".into()],
                is_unique: true,
                is_primary: true,
                ..IndexInfo::default()
            },
            IndexInfo {
                name: "idx_cat".into(),
                columns: vec!["category_id".into(), "name".into()],
                is_unique: false,
                is_primary: false,
                ..IndexInfo::default()
            },
        ];

        mark_indexed_columns(&mut columns, &indexes);

        assert!(columns[0].is_indexed);
        assert!(columns[1].is_indexed);
        assert!(columns[2].is_indexed);
    }

    #[test]
    fn leaves_non_indexed_columns_unmarked() {
        let mut columns = vec![ColumnInfo {
            name: "note".into(),
            data_type: "text".into(),
            nullable: true,
            is_indexed: false,
            ..ColumnInfo::default()
        }];
        mark_indexed_columns(&mut columns, &[]);
        assert!(!columns[0].is_indexed);
    }
}
