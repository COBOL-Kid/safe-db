use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QuerySpec {
    pub tables: Vec<TableRef>,
    pub columns: Vec<ColumnSel>,
    pub joins: Vec<JoinSpec>,
    pub filters: Vec<FilterSpec>,
    pub limit: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TableRef {
    pub schema: String,
    pub name: String,
    pub alias: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ColumnSel {
    pub table_alias: String,
    pub column: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JoinSpec {
    pub left_alias: String,
    pub left_column: String,
    pub right_alias: String,
    pub right_column: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilterSpec {
    pub table_alias: String,
    pub column: String,
    pub op: FilterOp,
    pub value: Option<String>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum FilterOp {
    Eq,
    Ne,
    Gt,
    Gte,
    Lt,
    Lte,
    Like,
    IsNull,
    IsNotNull,
}

impl FilterOp {
    pub fn sql(&self) -> &'static str {
        match self {
            FilterOp::Eq => "=",
            FilterOp::Ne => "<>",
            FilterOp::Gt => ">",
            FilterOp::Gte => ">=",
            FilterOp::Lt => "<",
            FilterOp::Lte => "<=",
            FilterOp::Like => "LIKE",
            FilterOp::IsNull => "IS NULL",
            FilterOp::IsNotNull => "IS NOT NULL",
        }
    }

    pub fn needs_value(&self) -> bool {
        !matches!(self, FilterOp::IsNull | FilterOp::IsNotNull)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QueryResult {
    pub columns: Vec<String>,
    pub rows: Vec<Vec<serde_json::Value>>,
    pub row_count: usize,
    pub truncated: bool,
    pub warnings: Vec<String>,
}

#[derive(Debug, Clone)]
pub struct CompiledQuery {
    pub sql: String,
    pub params: Vec<String>,
}
