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
