use serde::{Deserialize, Serialize};

pub const CURRENT_SCHEMA_VERSION: u32 = 2;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QuerySpec {
    pub tables: Vec<TableRef>,
    pub columns: Vec<ColumnSel>,
    pub joins: Vec<JoinSpec>,
    pub filters: FilterGroup,
    pub limit: u32,
    #[serde(default = "default_schema_version")]
    pub schema_version: u32,
}

fn default_schema_version() -> u32 {
    CURRENT_SCHEMA_VERSION
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
pub enum FilterNode {
    Leaf(FilterSpec),
    Group(FilterGroup),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilterGroup {
    pub connector: GroupConnector,
    pub children: Vec<FilterNode>,
}

impl Default for FilterGroup {
    fn default() -> Self {
        FilterGroup {
            connector: GroupConnector::And,
            children: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum GroupConnector {
    And,
    Or,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilterSpec {
    pub table_alias: String,
    pub column: String,
    pub op: FilterOp,
    pub value: Option<FilterValue>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum FilterValue {
    Single(FilterLiteral),
    List(Vec<FilterLiteral>),
    Pair(FilterLiteral, FilterLiteral),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilterLiteral {
    pub kind: LiteralKind,
    pub text: String,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum LiteralKind {
    Text,
    Int,
    Float,
    Bool,
    Date,
    DateTime,
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
    NotLike,
    Ilike,
    In,
    NotIn,
    Between,
    IsNull,
    IsNotNull,
    IsEmpty,
    IsNotEmpty,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ValueKind {
    None,
    Single,
    List,
    Pair,
}

impl FilterOp {
    pub fn value_kind(&self) -> ValueKind {
        match self {
            FilterOp::IsNull | FilterOp::IsNotNull | FilterOp::IsEmpty | FilterOp::IsNotEmpty => {
                ValueKind::None
            }
            FilterOp::In | FilterOp::NotIn => ValueKind::List,
            FilterOp::Between => ValueKind::Pair,
            _ => ValueKind::Single,
        }
    }

    pub fn needs_value(&self) -> bool {
        self.value_kind() != ValueKind::None
    }

    pub fn sql_operator(&self) -> Option<&'static str> {
        match self {
            FilterOp::Eq => Some("="),
            FilterOp::Ne => Some("<>"),
            FilterOp::Gt => Some(">"),
            FilterOp::Gte => Some(">="),
            FilterOp::Lt => Some("<"),
            FilterOp::Lte => Some("<="),
            FilterOp::Like => Some("LIKE"),
            FilterOp::NotLike => Some("NOT LIKE"),
            _ => None,
        }
    }
}

#[derive(Debug, Clone)]
pub enum BindValue {
    Text(String),
    Int(i64),
    Float(f64),
    Bool(bool),
    Null,
}

impl BindValue {
    pub fn from_literal(lit: &FilterLiteral) -> Result<Self, String> {
        match lit.kind {
            LiteralKind::Text => Ok(BindValue::Text(lit.text.clone())),
            LiteralKind::Int => lit
                .text
                .parse::<i64>()
                .map(BindValue::Int)
                .map_err(|_| format!("'{}' is not a valid integer", lit.text)),
            LiteralKind::Float => lit
                .text
                .parse::<f64>()
                .map(BindValue::Float)
                .map_err(|_| format!("'{}' is not a valid number", lit.text)),
            LiteralKind::Bool => lit
                .text
                .parse::<bool>()
                .or_else(|_| {
                    if lit.text.eq_ignore_ascii_case("true")
                        || lit.text.eq_ignore_ascii_case("1")
                        || lit.text.eq_ignore_ascii_case("yes")
                    {
                        Ok(true)
                    } else if lit.text.eq_ignore_ascii_case("false")
                        || lit.text.eq_ignore_ascii_case("0")
                        || lit.text.eq_ignore_ascii_case("no")
                        || lit.text.is_empty()
                    {
                        Ok(false)
                    } else {
                        Err(())
                    }
                })
                .map(BindValue::Bool)
                .map_err(|_| format!("'{}' is not a valid boolean", lit.text)),
            LiteralKind::Date | LiteralKind::DateTime => Ok(BindValue::Text(lit.text.clone())),
        }
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
    pub params: Vec<BindValue>,
}
