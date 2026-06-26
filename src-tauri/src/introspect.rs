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

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ColumnInfo {
    pub name: String,
    pub data_type: String,
    pub nullable: bool,
    pub is_indexed: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IndexInfo {
    pub name: String,
    pub columns: Vec<String>,
    pub is_unique: bool,
    pub is_primary: bool,
}

/// Mark which columns appear in at least one index, mutating `columns` in place.
pub fn mark_indexed_columns(columns: &mut [ColumnInfo], indexes: &[IndexInfo]) {
    let indexed: std::collections::HashSet<&str> = indexes
        .iter()
        .flat_map(|idx| idx.columns.iter().map(String::as_str))
        .collect();
    for col in columns.iter_mut() {
        col.is_indexed = indexed.contains(col.name.as_str());
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
            },
            ColumnInfo {
                name: "category_id".into(),
                data_type: "int".into(),
                nullable: false,
                is_indexed: false,
            },
            ColumnInfo {
                name: "name".into(),
                data_type: "text".into(),
                nullable: true,
                is_indexed: false,
            },
        ];
        let indexes = vec![
            IndexInfo {
                name: "pk".into(),
                columns: vec!["id".into()],
                is_unique: true,
                is_primary: true,
            },
            IndexInfo {
                name: "idx_cat".into(),
                columns: vec!["category_id".into(), "name".into()],
                is_unique: false,
                is_primary: false,
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
        }];
        mark_indexed_columns(&mut columns, &[]);
        assert!(!columns[0].is_indexed);
    }
}
