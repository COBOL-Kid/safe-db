use std::collections::HashSet;

use crate::introspect::{ColumnCategory, Schema, TableInfo, classify_column};
use crate::query::ir::{
    FilterGroup, FilterLiteral, FilterNode, FilterOp, FilterSpec, FilterValue, LiteralKind,
    QuerySpec, ValueKind,
};

pub const MAX_LIMIT: u32 = 1000;
pub const DEFAULT_LIMIT: u32 = 100;
pub const MAX_FILTER_DEPTH: usize = 5;
pub const MAX_IN_LIST_SIZE: usize = 1000;
pub const MAX_TEXT_LITERAL_LEN: usize = 10_000;

const BLOCKED_SCHEMAS: &[&str] = &[
    "pg_catalog",
    "information_schema",
    "pg_toast",
    "mysql",
    "performance_schema",
    "sys",
    "guest",
    "INFORMATION_SCHEMA",
    "SYS",
    "SYSTEM",
    "OUTLN",
    "DBSNMP",
    "XDB",
    "CTXSYS",
    "MDSYS",
    "OLAPSYS",
    "WMSYS",
    "ORDSYS",
    "EXFSYS",
    "ANONYMOUS",
    "APEX_PUBLIC_USER",
    "FLOWS_FILES",
    "APEX_030200",
    "APEX_040000",
    "APEX_040200",
    "AUDSYS",
    "GSMADMIN_INTERNAL",
    "SYSMAN",
    "DBSFWUSER",
    "APPQOSSYS",
    "ORACLE_OCM",
    "XS$NULL",
    "DVSYS",
    "LBACSYS",
];

#[derive(Debug)]
pub struct ValidationOutcome {
    pub warnings: Vec<String>,
    pub limit: u32,
}

#[derive(Debug, Clone)]
pub struct ValidatedColumn {
    pub table_alias: String,
    pub column: String,
    pub result_alias: String,
}

#[derive(Debug, Clone)]
pub struct ValidatedQuery {
    spec: QuerySpec,
    columns: Vec<ValidatedColumn>,
}

impl ValidatedQuery {
    pub(crate) fn spec(&self) -> &QuerySpec {
        &self.spec
    }

    pub(crate) fn columns(&self) -> &[ValidatedColumn] {
        &self.columns
    }
}

pub fn validate_query(
    spec: &mut QuerySpec,
    schema: &Schema,
    custom_blocked: &[String],
) -> Result<(ValidatedQuery, ValidationOutcome), String> {
    let outcome = validate(spec, schema, custom_blocked)?;
    let selections = if spec.columns.is_empty() {
        spec.tables
            .iter()
            .flat_map(|table_ref| {
                find_table(schema, &table_ref.schema, &table_ref.name)
                    .into_iter()
                    .flat_map(move |table| {
                        table
                            .columns
                            .iter()
                            .map(move |column| crate::query::ir::ColumnSel {
                                table_alias: table_ref.alias.clone(),
                                column: column.name.clone(),
                            })
                    })
            })
            .collect::<Vec<_>>()
    } else {
        spec.columns.clone()
    };
    let mut aliases = HashSet::new();
    let columns = selections
        .into_iter()
        .map(|selection| {
            let base = format!("{}__{}", selection.table_alias, selection.column);
            let mut result_alias = base.clone();
            let mut suffix = 2usize;
            while !aliases.insert(result_alias.clone()) {
                result_alias = format!("{base}__{suffix}");
                suffix += 1;
            }
            ValidatedColumn {
                table_alias: selection.table_alias,
                column: selection.column,
                result_alias,
            }
        })
        .collect();
    Ok((
        ValidatedQuery {
            spec: spec.clone(),
            columns,
        },
        outcome,
    ))
}

pub fn literal_kind_for_column(data_type: &str) -> LiteralKind {
    match classify_column(data_type) {
        ColumnCategory::Integer => LiteralKind::Int,
        ColumnCategory::Decimal => LiteralKind::Decimal,
        ColumnCategory::Bool => LiteralKind::Bool,
        ColumnCategory::Date => LiteralKind::Date,
        ColumnCategory::DateTime => LiteralKind::DateTime,
        ColumnCategory::Text
        | ColumnCategory::Binary
        | ColumnCategory::Json
        | ColumnCategory::Other => LiteralKind::Text,
    }
}

pub fn ops_for_column(data_type: &str) -> &'static [FilterOp] {
    match classify_column(data_type) {
        ColumnCategory::Text => &[
            FilterOp::Eq,
            FilterOp::Ne,
            FilterOp::Like,
            FilterOp::NotLike,
            FilterOp::Ilike,
            FilterOp::In,
            FilterOp::NotIn,
            FilterOp::IsNull,
            FilterOp::IsNotNull,
            FilterOp::IsEmpty,
            FilterOp::IsNotEmpty,
        ],
        ColumnCategory::Integer | ColumnCategory::Decimal => &[
            FilterOp::Eq,
            FilterOp::Ne,
            FilterOp::Gt,
            FilterOp::Gte,
            FilterOp::Lt,
            FilterOp::Lte,
            FilterOp::In,
            FilterOp::NotIn,
            FilterOp::Between,
            FilterOp::IsNull,
            FilterOp::IsNotNull,
        ],
        ColumnCategory::Bool => &[
            FilterOp::Eq,
            FilterOp::Ne,
            FilterOp::IsNull,
            FilterOp::IsNotNull,
        ],
        ColumnCategory::Date | ColumnCategory::DateTime => &[
            FilterOp::Eq,
            FilterOp::Ne,
            FilterOp::Gt,
            FilterOp::Gte,
            FilterOp::Lt,
            FilterOp::Lte,
            FilterOp::Between,
            FilterOp::IsNull,
            FilterOp::IsNotNull,
        ],
        ColumnCategory::Binary | ColumnCategory::Json | ColumnCategory::Other => &[
            FilterOp::Eq,
            FilterOp::Ne,
            FilterOp::IsNull,
            FilterOp::IsNotNull,
        ],
    }
}

pub fn validate(
    spec: &mut QuerySpec,
    schema: &Schema,
    custom_blocked: &[String],
) -> Result<ValidationOutcome, String> {
    let mut warnings = Vec::new();

    if spec.schema_version != crate::query::ir::CURRENT_SCHEMA_VERSION {
        return Err(format!(
            "Query schema version {} is unsupported; expected {}",
            spec.schema_version,
            crate::query::ir::CURRENT_SCHEMA_VERSION
        ));
    }
    let mut node_ids = HashSet::new();
    collect_node_ids(&spec.filters, &mut node_ids)?;
    for override_id in spec.connector_overrides.keys() {
        if !node_ids.contains(override_id.as_str()) {
            return Err(format!(
                "Connector override references unknown filter node id '{override_id}'"
            ));
        }
    }

    if spec.tables.is_empty() {
        return Err("At least one table is required".to_string());
    }

    let mut table_aliases: HashSet<&str> = HashSet::new();
    for table in &spec.tables {
        if is_blocked(&table.schema, custom_blocked) {
            return Err(format!(
                "Schema '{}' is blocked (system/catalog schema)",
                table.schema
            ));
        }

        let _table_info = find_table(schema, &table.schema, &table.name).ok_or_else(|| {
            format!(
                "Table '{}.{}' not found in schema",
                table.schema, table.name
            )
        })?;

        if !table_aliases.insert(table.alias.as_str()) {
            return Err(format!("Duplicate table alias '{}'", table.alias));
        }

        for col in &spec.columns {
            if col.table_alias == table.alias
                && !find_table(schema, &table.schema, &table.name)
                    .map(|t| t.columns.iter().any(|c| c.name == col.column))
                    .unwrap_or(false)
            {
                return Err(format!(
                    "Column '{}.{}' does not exist",
                    table.alias, col.column
                ));
            }
        }
    }

    for col in &spec.columns {
        if !table_aliases.contains(col.table_alias.as_str()) {
            return Err(format!(
                "Column selection references unknown table alias '{}'",
                col.table_alias
            ));
        }
    }

    for join in &spec.joins {
        if !table_aliases.contains(join.left_alias.as_str()) {
            return Err(format!(
                "Join references unknown table alias '{}'",
                join.left_alias
            ));
        }
        if !table_aliases.contains(join.right_alias.as_str()) {
            return Err(format!(
                "Join references unknown table alias '{}'",
                join.right_alias
            ));
        }

        let left_table = find_table_by_alias(schema, spec, &join.left_alias)
            .ok_or_else(|| format!("Cannot resolve table for alias '{}'", join.left_alias))?;
        let right_table = find_table_by_alias(schema, spec, &join.right_alias)
            .ok_or_else(|| format!("Cannot resolve table for alias '{}'", join.right_alias))?;

        let left_col = left_table
            .columns
            .iter()
            .find(|c| c.name == join.left_column)
            .ok_or_else(|| {
                format!(
                    "Join column '{}.{}' does not exist",
                    join.left_alias, join.left_column
                )
            })?;
        let right_col = right_table
            .columns
            .iter()
            .find(|c| c.name == join.right_column)
            .ok_or_else(|| {
                format!(
                    "Join column '{}.{}' does not exist",
                    join.right_alias, join.right_column
                )
            })?;

        if !left_col.join_eligible {
            return Err(format!(
                "Join column '{}.{}' is not the leading key of an equality-capable index",
                join.left_alias, join.left_column
            ));
        }
        if !right_col.join_eligible {
            return Err(format!(
                "Join column '{}.{}' is not the leading key of an equality-capable index",
                join.right_alias, join.right_column
            ));
        }
        if left_col.category != right_col.category {
            return Err(format!(
                "Join columns '{}.{}' and '{}.{}' have incompatible types",
                join.left_alias, join.left_column, join.right_alias, join.right_column
            ));
        }
    }

    validate_node(
        &FilterNode::Group(spec.filters.clone()),
        schema,
        spec,
        &table_aliases,
        0,
        &mut warnings,
    )?;

    if spec.tables.len() > 1 && !check_join_connectivity(spec) {
        return Err(
            "Not all tables are connected by joins — add joins linking every table".to_string(),
        );
    }

    if spec.limit == 0 {
        spec.limit = DEFAULT_LIMIT;
        warnings.push(format!("Limit was 0; defaulted to {}", DEFAULT_LIMIT));
    }
    if spec.limit > MAX_LIMIT {
        warnings.push(format!(
            "Limit {} exceeds maximum {}; capped to {}",
            spec.limit, MAX_LIMIT, MAX_LIMIT
        ));
        spec.limit = MAX_LIMIT;
    }

    if spec.columns.is_empty() {
        warnings.push("No columns selected — query will select all columns".to_string());
    }

    Ok(ValidationOutcome {
        warnings,
        limit: spec.limit,
    })
}

fn insert_node_id<'a>(id: &'a str, node_ids: &mut HashSet<&'a str>) -> Result<(), String> {
    if id.trim().is_empty() {
        return Err("Every filter node must have a non-empty stable id".to_string());
    }
    if !node_ids.insert(id) {
        return Err(format!("Duplicate filter node id '{id}'"));
    }
    Ok(())
}

fn collect_node_ids<'a>(
    group: &'a FilterGroup,
    node_ids: &mut HashSet<&'a str>,
) -> Result<(), String> {
    insert_node_id(&group.id, node_ids)?;
    for child in &group.children {
        match child {
            FilterNode::Leaf(leaf) => insert_node_id(&leaf.id, node_ids)?,
            FilterNode::Group(nested) => collect_node_ids(nested, node_ids)?,
        }
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn validate_node(
    node: &FilterNode,
    schema: &Schema,
    spec: &QuerySpec,
    table_aliases: &HashSet<&str>,
    depth: usize,
    warnings: &mut Vec<String>,
) -> Result<(), String> {
    if depth > MAX_FILTER_DEPTH {
        return Err(format!(
            "Filter nesting exceeds maximum depth of {}",
            MAX_FILTER_DEPTH
        ));
    }

    match node {
        FilterNode::Leaf(filter) => validate_leaf(filter, schema, spec, table_aliases),
        FilterNode::Group(group) => {
            validate_group(group, schema, spec, table_aliases, depth, warnings)
        }
    }
}

fn validate_leaf(
    filter: &FilterSpec,
    schema: &Schema,
    spec: &QuerySpec,
    table_aliases: &HashSet<&str>,
) -> Result<(), String> {
    if !table_aliases.contains(filter.table_alias.as_str()) {
        return Err(format!(
            "Filter references unknown table alias '{}'",
            filter.table_alias
        ));
    }

    let table = find_table_by_alias(schema, spec, &filter.table_alias)
        .ok_or_else(|| format!("Cannot resolve table for alias '{}'", filter.table_alias))?;

    let col = table
        .columns
        .iter()
        .find(|c| c.name == filter.column)
        .ok_or_else(|| {
            format!(
                "Filter column '{}.{}' does not exist",
                filter.table_alias, filter.column
            )
        })?;

    let allowed = ops_for_column(&col.data_type);
    if !allowed.contains(&filter.op) {
        return Err(format!(
            "Operator '{}' is not applicable to column '{}.{}' (type: {})",
            op_label(&filter.op),
            filter.table_alias,
            filter.column,
            col.data_type
        ));
    }

    let value_kind = filter.op.value_kind();
    match value_kind {
        ValueKind::None => {
            if filter.value.is_some() {
                return Err(format!(
                    "Operator '{}' on '{}.{}' should not have a value",
                    op_label(&filter.op),
                    filter.table_alias,
                    filter.column
                ));
            }
        }
        ValueKind::Single => {
            let val = filter.value.as_ref().ok_or_else(|| {
                format!(
                    "Filter on '{}.{}' requires a value",
                    filter.table_alias, filter.column
                )
            })?;
            let lit = expect_single(val, &filter.table_alias, &filter.column)?;
            validate_literal(lit, &col.data_type, &filter.table_alias, &filter.column)?;
        }
        ValueKind::List => {
            let val = filter.value.as_ref().ok_or_else(|| {
                format!(
                    "Filter on '{}.{}' requires a value list",
                    filter.table_alias, filter.column
                )
            })?;
            let list = expect_list(val, &filter.table_alias, &filter.column)?;
            if list.is_empty() {
                return Err(format!(
                    "Filter on '{}.{}' has an empty value list",
                    filter.table_alias, filter.column
                ));
            }
            if list.len() > MAX_IN_LIST_SIZE {
                return Err(format!(
                    "Filter on '{}.{}' has too many values (max {})",
                    filter.table_alias, filter.column, MAX_IN_LIST_SIZE
                ));
            }
            for lit in list {
                validate_literal(lit, &col.data_type, &filter.table_alias, &filter.column)?;
            }
        }
        ValueKind::Pair => {
            let val = filter.value.as_ref().ok_or_else(|| {
                format!(
                    "Filter on '{}.{}' requires a range (from/to)",
                    filter.table_alias, filter.column
                )
            })?;
            let (from, to) = expect_pair(val, &filter.table_alias, &filter.column)?;
            validate_literal(from, &col.data_type, &filter.table_alias, &filter.column)?;
            validate_literal(to, &col.data_type, &filter.table_alias, &filter.column)?;
        }
    }

    Ok(())
}

fn validate_group(
    group: &FilterGroup,
    schema: &Schema,
    spec: &QuerySpec,
    table_aliases: &HashSet<&str>,
    depth: usize,
    warnings: &mut Vec<String>,
) -> Result<(), String> {
    if depth > 0 && group.children.is_empty() {
        warnings.push("Filter group has no conditions".to_string());
    }
    if depth > 0 && group.children.len() == 1 && matches!(group.children[0], FilterNode::Leaf(_)) {
        warnings.push("Filter group has only one condition — a group is unnecessary".to_string());
    }
    for child in &group.children {
        validate_node(child, schema, spec, table_aliases, depth + 1, warnings)?;
    }
    Ok(())
}

fn validate_literal(
    lit: &FilterLiteral,
    data_type: &str,
    alias: &str,
    column: &str,
) -> Result<(), String> {
    let expected = literal_kind_for_column(data_type);
    if lit.kind != expected {
        return Err(format!(
            "Value for '{}.{}' has type {:?}; expected {:?} for column type {}",
            alias, column, lit.kind, expected, data_type
        ));
    }
    match lit.kind {
        LiteralKind::Text => {
            if lit.text.len() > MAX_TEXT_LITERAL_LEN {
                return Err(format!(
                    "Text value for '{}.{}' exceeds maximum length of {} characters",
                    alias, column, MAX_TEXT_LITERAL_LEN
                ));
            }
            Ok(())
        }
        LiteralKind::Int => {
            lit.text.parse::<i64>().map_err(|_| {
                format!(
                    "'{}' is not a valid integer for '{}.{}'",
                    lit.text, alias, column
                )
            })?;
            Ok(())
        }
        LiteralKind::Decimal => {
            lit.text.parse::<bigdecimal::BigDecimal>().map_err(|_| {
                format!(
                    "'{}' is not a valid decimal for '{}.{}'",
                    lit.text, alias, column
                )
            })?;
            Ok(())
        }
        LiteralKind::Float => {
            lit.text.parse::<f64>().map_err(|_| {
                format!(
                    "'{}' is not a valid number for '{}.{}'",
                    lit.text, alias, column
                )
            })?;
            Ok(())
        }
        LiteralKind::Bool => {
            if lit.text.eq_ignore_ascii_case("true")
                || lit.text.eq_ignore_ascii_case("false")
                || lit.text.eq_ignore_ascii_case("1")
                || lit.text.eq_ignore_ascii_case("0")
                || lit.text.eq_ignore_ascii_case("yes")
                || lit.text.eq_ignore_ascii_case("no")
                || lit.text.is_empty()
            {
                Ok(())
            } else {
                Err(format!(
                    "'{}' is not a valid boolean for '{}.{}'",
                    lit.text, alias, column
                ))
            }
        }
        LiteralKind::Date => crate::query::ir::parse_date_literal(&lit.text)
            .map(|_| ())
            .map_err(|e| format!("{e} for '{}.{}'", alias, column)),
        LiteralKind::DateTime => crate::query::ir::parse_datetime_literal(&lit.text)
            .map(|_| ())
            .map_err(|e| format!("{e} for '{}.{}'", alias, column)),
    }
}

fn expect_single<'a>(
    val: &'a FilterValue,
    alias: &str,
    column: &str,
) -> Result<&'a FilterLiteral, String> {
    match val {
        FilterValue::Single(lit) => Ok(lit),
        _ => Err(format!(
            "Filter on '{}.{}' expects a single value",
            alias, column
        )),
    }
}

fn expect_list<'a>(
    val: &'a FilterValue,
    alias: &str,
    column: &str,
) -> Result<&'a Vec<FilterLiteral>, String> {
    match val {
        FilterValue::List(list) => Ok(list),
        _ => Err(format!(
            "Filter on '{}.{}' expects a list of values",
            alias, column
        )),
    }
}

fn expect_pair<'a>(
    val: &'a FilterValue,
    alias: &str,
    column: &str,
) -> Result<(&'a FilterLiteral, &'a FilterLiteral), String> {
    match val {
        FilterValue::Pair(from, to) => Ok((from, to)),
        _ => Err(format!(
            "Filter on '{}.{}' expects a range (from/to)",
            alias, column
        )),
    }
}

fn op_label(op: &FilterOp) -> &'static str {
    match op {
        FilterOp::Eq => "=",
        FilterOp::Ne => "<>",
        FilterOp::Gt => ">",
        FilterOp::Gte => ">=",
        FilterOp::Lt => "<",
        FilterOp::Lte => "<=",
        FilterOp::Like => "LIKE",
        FilterOp::NotLike => "NOT LIKE",
        FilterOp::Ilike => "ILIKE",
        FilterOp::In => "IN",
        FilterOp::NotIn => "NOT IN",
        FilterOp::Between => "BETWEEN",
        FilterOp::IsNull => "IS NULL",
        FilterOp::IsNotNull => "IS NOT NULL",
        FilterOp::IsEmpty => "IS EMPTY",
        FilterOp::IsNotEmpty => "IS NOT EMPTY",
    }
}

fn find_table<'a>(
    schema: &'a Schema,
    table_schema: &str,
    table_name: &str,
) -> Option<&'a TableInfo> {
    schema
        .tables
        .iter()
        .find(|t| t.schema == table_schema && t.name == table_name)
}

fn find_table_by_alias<'a>(
    schema: &'a Schema,
    spec: &QuerySpec,
    alias: &str,
) -> Option<&'a TableInfo> {
    let table_ref = spec.tables.iter().find(|t| t.alias == alias)?;
    find_table(schema, &table_ref.schema, &table_ref.name)
}

fn check_join_connectivity(spec: &QuerySpec) -> bool {
    if spec.tables.len() <= 1 {
        return true;
    }

    let mut connected: HashSet<&str> = HashSet::new();
    connected.insert(spec.tables[0].alias.as_str());

    let mut changed = true;
    while changed {
        changed = false;
        for join in &spec.joins {
            let left = join.left_alias.as_str();
            let right = join.right_alias.as_str();
            if connected.contains(left) && !connected.contains(right) {
                connected.insert(right);
                changed = true;
            } else if connected.contains(right) && !connected.contains(left) {
                connected.insert(left);
                changed = true;
            }
        }
    }

    connected.len() == spec.tables.len()
}

fn is_blocked(schema: &str, custom: &[String]) -> bool {
    BLOCKED_SCHEMAS
        .iter()
        .any(|blocked| blocked.eq_ignore_ascii_case(schema))
        || custom.iter().any(|s| s.eq_ignore_ascii_case(schema))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::introspect::{ColumnInfo, Schema, TableInfo};
    use crate::query::ir::{
        CURRENT_SCHEMA_VERSION, ColumnSel, FilterGroup, FilterLiteral, FilterNode, FilterOp,
        FilterSpec, FilterValue, GroupConnector, JoinSpec, LiteralKind, QuerySpec, TableRef,
    };

    fn sample_schema() -> Schema {
        Schema {
            tables: vec![
                TableInfo {
                    schema: "public".into(),
                    name: "products".into(),
                    columns: vec![
                        ColumnInfo {
                            name: "id".into(),
                            data_type: "int".into(),
                            nullable: false,
                            is_indexed: true,
                            join_eligible: true,
                            category: ColumnCategory::Integer,
                        },
                        ColumnInfo {
                            name: "name".into(),
                            data_type: "text".into(),
                            nullable: true,
                            is_indexed: false,
                            ..ColumnInfo::default()
                        },
                        ColumnInfo {
                            name: "price".into(),
                            data_type: "numeric".into(),
                            nullable: true,
                            is_indexed: false,
                            ..ColumnInfo::default()
                        },
                        ColumnInfo {
                            name: "active".into(),
                            data_type: "boolean".into(),
                            nullable: true,
                            is_indexed: false,
                            ..ColumnInfo::default()
                        },
                        ColumnInfo {
                            name: "created_at".into(),
                            data_type: "timestamp".into(),
                            nullable: true,
                            is_indexed: false,
                            ..ColumnInfo::default()
                        },
                    ],
                    indexes: vec![],
                },
                TableInfo {
                    schema: "public".into(),
                    name: "categories".into(),
                    columns: vec![ColumnInfo {
                        name: "id".into(),
                        data_type: "int".into(),
                        nullable: false,
                        is_indexed: true,
                        join_eligible: true,
                        category: ColumnCategory::Integer,
                    }],
                    indexes: vec![],
                },
            ],
        }
    }

    fn base_spec() -> QuerySpec {
        QuerySpec {
            tables: vec![TableRef {
                schema: "public".into(),
                name: "products".into(),
                alias: "t0".into(),
            }],
            columns: vec![ColumnSel {
                table_alias: "t0".into(),
                column: "id".into(),
            }],
            joins: vec![],
            filters: FilterGroup::default(),
            limit: 100,
            connector_overrides: std::collections::BTreeMap::new(),
            schema_version: CURRENT_SCHEMA_VERSION,
        }
    }

    fn lit(kind: LiteralKind, text: &str) -> FilterLiteral {
        FilterLiteral {
            kind,
            text: text.into(),
        }
    }

    fn leaf(op: FilterOp, value: Option<FilterValue>) -> FilterSpec {
        FilterSpec {
            id: uuid::Uuid::new_v4().to_string(),
            table_alias: "t0".into(),
            column: "name".into(),
            op,
            value,
        }
    }

    fn leaf_on(col: &str, op: FilterOp, value: Option<FilterValue>) -> FilterSpec {
        FilterSpec {
            id: uuid::Uuid::new_v4().to_string(),
            table_alias: "t0".into(),
            column: col.into(),
            op,
            value,
        }
    }

    fn group(connector: GroupConnector, children: Vec<FilterNode>) -> FilterGroup {
        FilterGroup {
            id: uuid::Uuid::new_v4().to_string(),
            connector,
            children,
        }
    }

    #[test]
    fn rejects_empty_tables() {
        let mut spec = base_spec();
        spec.tables.clear();
        let err = validate(&mut spec, &sample_schema(), &[]).unwrap_err();
        assert!(err.contains("At least one table"));
    }

    #[test]
    fn rejects_blocked_system_schema() {
        let mut spec = base_spec();
        spec.tables[0].schema = "pg_catalog".into();
        let err = validate(&mut spec, &sample_schema(), &[]).unwrap_err();
        assert!(err.contains("blocked"));
    }

    #[test]
    fn rejects_custom_blocked_schema_case_insensitive() {
        let mut spec = base_spec();
        spec.tables[0].schema = "Audit".into();
        let err = validate(&mut spec, &sample_schema(), &["audit".into()]).unwrap_err();
        assert!(err.contains("blocked"));
    }

    #[test]
    fn rejects_custom_blocked_schema() {
        let mut spec = base_spec();
        spec.tables[0].schema = "audit".into();
        let err = validate(&mut spec, &sample_schema(), &["audit".into()]).unwrap_err();
        assert!(err.contains("blocked"));
    }

    #[test]
    fn rejects_join_on_non_indexed_column() {
        let mut spec = base_spec();
        spec.tables.push(TableRef {
            schema: "public".into(),
            name: "categories".into(),
            alias: "t1".into(),
        });
        spec.joins.push(JoinSpec {
            left_alias: "t0".into(),
            left_column: "name".into(),
            right_alias: "t1".into(),
            right_column: "id".into(),
        });
        let err = validate(&mut spec, &sample_schema(), &[]).unwrap_err();
        assert!(err.contains("leading key"));
    }

    #[test]
    fn accepts_join_on_indexed_columns() {
        let mut spec = base_spec();
        spec.tables.push(TableRef {
            schema: "public".into(),
            name: "categories".into(),
            alias: "t1".into(),
        });
        spec.joins.push(JoinSpec {
            left_alias: "t0".into(),
            left_column: "id".into(),
            right_alias: "t1".into(),
            right_column: "id".into(),
        });
        let outcome = validate(&mut spec, &sample_schema(), &[]).unwrap();
        assert!(outcome.warnings.is_empty());
    }

    #[test]
    fn errors_on_disconnected_tables() {
        let mut spec = base_spec();
        spec.tables.push(TableRef {
            schema: "public".into(),
            name: "categories".into(),
            alias: "t1".into(),
        });
        let err = validate(&mut spec, &sample_schema(), &[]).unwrap_err();
        assert!(err.contains("connected by joins"));
    }

    #[test]
    fn defaults_zero_limit_and_caps_excess() {
        let mut spec = base_spec();
        spec.limit = 0;
        let outcome = validate(&mut spec, &sample_schema(), &[]).unwrap();
        assert_eq!(spec.limit, DEFAULT_LIMIT);
        assert!(outcome.warnings.iter().any(|w| w.contains("defaulted")));

        spec.limit = 5000;
        let outcome = validate(&mut spec, &sample_schema(), &[]).unwrap();
        assert_eq!(spec.limit, MAX_LIMIT);
        assert_eq!(outcome.limit, MAX_LIMIT);
    }

    #[test]
    fn warns_when_no_columns_selected() {
        let mut spec = base_spec();
        spec.columns.clear();
        let outcome = validate(&mut spec, &sample_schema(), &[]).unwrap();
        assert!(
            outcome
                .warnings
                .iter()
                .any(|w| w.contains("No columns selected"))
        );
    }

    #[test]
    fn rejects_filter_missing_value() {
        let mut spec = base_spec();
        spec.filters
            .children
            .push(FilterNode::Leaf(leaf(FilterOp::Eq, None)));
        let err = validate(&mut spec, &sample_schema(), &[]).unwrap_err();
        assert!(err.contains("requires a value"));
    }

    #[test]
    fn rejects_ilike_on_numeric_column() {
        let mut spec = base_spec();
        spec.filters.children.push(FilterNode::Leaf(leaf_on(
            "id",
            FilterOp::Ilike,
            Some(FilterValue::Single(lit(LiteralKind::Text, "foo"))),
        )));
        let err = validate(&mut spec, &sample_schema(), &[]).unwrap_err();
        assert!(err.contains("not applicable"));
    }

    #[test]
    fn rejects_is_empty_on_numeric_column() {
        let mut spec = base_spec();
        spec.filters
            .children
            .push(FilterNode::Leaf(leaf_on("id", FilterOp::IsEmpty, None)));
        let err = validate(&mut spec, &sample_schema(), &[]).unwrap_err();
        assert!(err.contains("not applicable"));
    }

    #[test]
    fn accepts_is_empty_on_text_column() {
        let mut spec = base_spec();
        spec.filters
            .children
            .push(FilterNode::Leaf(leaf(FilterOp::IsEmpty, None)));
        let outcome = validate(&mut spec, &sample_schema(), &[]).unwrap();
        assert!(
            outcome
                .warnings
                .iter()
                .all(|w| !w.contains("not applicable"))
        );
    }

    #[test]
    fn rejects_in_with_empty_list() {
        let mut spec = base_spec();
        spec.filters.children.push(FilterNode::Leaf(leaf_on(
            "id",
            FilterOp::In,
            Some(FilterValue::List(vec![])),
        )));
        let err = validate(&mut spec, &sample_schema(), &[]).unwrap_err();
        assert!(err.contains("empty value list"));
    }

    #[test]
    fn accepts_in_with_values() {
        let mut spec = base_spec();
        spec.filters.children.push(FilterNode::Leaf(leaf_on(
            "id",
            FilterOp::In,
            Some(FilterValue::List(vec![
                lit(LiteralKind::Int, "1"),
                lit(LiteralKind::Int, "2"),
            ])),
        )));
        validate(&mut spec, &sample_schema(), &[]).unwrap();
    }

    #[test]
    fn rejects_between_missing_pair() {
        let mut spec = base_spec();
        spec.filters.children.push(FilterNode::Leaf(leaf_on(
            "id",
            FilterOp::Between,
            Some(FilterValue::Single(lit(LiteralKind::Int, "5"))),
        )));
        let err = validate(&mut spec, &sample_schema(), &[]).unwrap_err();
        assert!(err.contains("range"));
    }

    #[test]
    fn accepts_between_with_pair() {
        let mut spec = base_spec();
        spec.filters.children.push(FilterNode::Leaf(leaf_on(
            "id",
            FilterOp::Between,
            Some(FilterValue::Pair(
                lit(LiteralKind::Int, "1"),
                lit(LiteralKind::Int, "100"),
            )),
        )));
        validate(&mut spec, &sample_schema(), &[]).unwrap();
    }

    #[test]
    fn rejects_non_integer_value_for_int_column() {
        let mut spec = base_spec();
        spec.filters.children.push(FilterNode::Leaf(leaf_on(
            "id",
            FilterOp::Eq,
            Some(FilterValue::Single(lit(LiteralKind::Int, "abc"))),
        )));
        let err = validate(&mut spec, &sample_schema(), &[]).unwrap_err();
        assert!(err.contains("not a valid integer"));
    }

    #[test]
    fn rejects_text_literal_kind_for_int_column() {
        let mut spec = base_spec();
        spec.filters.children.push(FilterNode::Leaf(leaf_on(
            "id",
            FilterOp::Eq,
            Some(FilterValue::Single(lit(LiteralKind::Text, "123"))),
        )));
        let err = validate(&mut spec, &sample_schema(), &[]).unwrap_err();
        assert!(err.contains("expected Int"), "got: {err}");
    }

    #[test]
    fn rejects_mismatched_literal_kind_inside_list() {
        let mut spec = base_spec();
        spec.filters.children.push(FilterNode::Leaf(leaf_on(
            "id",
            FilterOp::In,
            Some(FilterValue::List(vec![
                lit(LiteralKind::Int, "1"),
                lit(LiteralKind::Text, "2"),
            ])),
        )));
        let err = validate(&mut spec, &sample_schema(), &[]).unwrap_err();
        assert!(err.contains("expected Int"), "got: {err}");
    }

    #[test]
    fn rejects_mismatched_literal_kind_inside_pair() {
        let mut spec = base_spec();
        spec.filters.children.push(FilterNode::Leaf(leaf_on(
            "created_at",
            FilterOp::Between,
            Some(FilterValue::Pair(
                lit(LiteralKind::DateTime, "2025-01-01T00:00"),
                lit(LiteralKind::Text, "2025-01-02T00:00"),
            )),
        )));
        let err = validate(&mut spec, &sample_schema(), &[]).unwrap_err();
        assert!(err.contains("expected DateTime"), "got: {err}");
    }

    #[test]
    fn accepts_nested_groups() {
        let mut spec = base_spec();
        spec.filters.connector = GroupConnector::And;
        spec.filters.children.push(FilterNode::Group(group(
            GroupConnector::Or,
            vec![
                FilterNode::Leaf(leaf_on(
                    "id",
                    FilterOp::Eq,
                    Some(FilterValue::Single(lit(LiteralKind::Int, "1"))),
                )),
                FilterNode::Leaf(leaf_on(
                    "id",
                    FilterOp::Eq,
                    Some(FilterValue::Single(lit(LiteralKind::Int, "2"))),
                )),
            ],
        )));
        let outcome = validate(&mut spec, &sample_schema(), &[]).unwrap();
        assert!(outcome.warnings.is_empty());
    }

    #[test]
    fn warns_on_empty_group() {
        let mut spec = base_spec();
        spec.filters
            .children
            .push(FilterNode::Group(group(GroupConnector::Or, vec![])));
        let outcome = validate(&mut spec, &sample_schema(), &[]).unwrap();
        assert!(outcome.warnings.iter().any(|w| w.contains("no conditions")));
    }

    #[test]
    fn rejects_excessive_nesting() {
        let mut spec = base_spec();
        let mut deepest: &mut FilterGroup = &mut spec.filters;
        for _ in 0..(MAX_FILTER_DEPTH + 2) {
            deepest
                .children
                .push(FilterNode::Group(group(GroupConnector::And, vec![])));
            match deepest.children.last_mut().unwrap() {
                FilterNode::Group(g) => deepest = g,
                _ => break,
            }
        }
        let err = validate(&mut spec, &sample_schema(), &[]).unwrap_err();
        assert!(err.contains("maximum depth"));
    }

    #[test]
    fn classify_column_covers_common_types() {
        assert_eq!(classify_column("int"), ColumnCategory::Integer);
        assert_eq!(classify_column("INTEGER"), ColumnCategory::Integer);
        assert_eq!(classify_column("number"), ColumnCategory::Decimal);
        assert_eq!(classify_column("NUMBER"), ColumnCategory::Decimal);
        assert_eq!(classify_column("varchar"), ColumnCategory::Text);
        assert_eq!(classify_column("VARCHAR2"), ColumnCategory::Text);
        assert_eq!(classify_column("boolean"), ColumnCategory::Bool);
        assert_eq!(classify_column("date"), ColumnCategory::Date);
        assert_eq!(
            classify_column("timestamp without time zone"),
            ColumnCategory::DateTime
        );
        assert_eq!(classify_column("datetime"), ColumnCategory::DateTime);
    }

    #[test]
    fn in_list_at_max_size_is_accepted() {
        let mut spec = base_spec();
        let values: Vec<FilterLiteral> = (0..MAX_IN_LIST_SIZE)
            .map(|i| lit(LiteralKind::Int, &i.to_string()))
            .collect();
        spec.filters.children.push(FilterNode::Leaf(leaf_on(
            "id",
            FilterOp::In,
            Some(FilterValue::List(values)),
        )));
        validate(&mut spec, &sample_schema(), &[]).unwrap();
    }

    #[test]
    fn in_list_over_max_size_is_rejected() {
        let mut spec = base_spec();
        let values: Vec<FilterLiteral> = (0..=MAX_IN_LIST_SIZE)
            .map(|i| lit(LiteralKind::Int, &i.to_string()))
            .collect();
        spec.filters.children.push(FilterNode::Leaf(leaf_on(
            "id",
            FilterOp::In,
            Some(FilterValue::List(values)),
        )));
        let err = validate(&mut spec, &sample_schema(), &[]).unwrap_err();
        assert!(err.contains("too many values"));
    }

    fn nested_chain(levels: usize) -> FilterNode {
        let leaf = FilterNode::Leaf(leaf_on(
            "id",
            FilterOp::Eq,
            Some(FilterValue::Single(lit(LiteralKind::Int, "1"))),
        ));
        let mut node = leaf;
        for _ in 0..levels {
            node = FilterNode::Group(group(GroupConnector::And, vec![node]));
        }
        node
    }

    #[test]
    fn filter_depth_at_max_is_accepted() {
        // Root group is depth 0; 4 nested groups put the leaf at depth 5 (== MAX_FILTER_DEPTH).
        let mut spec = base_spec();
        spec.filters
            .children
            .push(nested_chain(MAX_FILTER_DEPTH - 1));
        validate(&mut spec, &sample_schema(), &[]).unwrap();
    }

    #[test]
    fn filter_depth_over_max_is_rejected() {
        // 5 nested groups put the leaf at depth 6 (> MAX_FILTER_DEPTH).
        let mut spec = base_spec();
        spec.filters.children.push(nested_chain(MAX_FILTER_DEPTH));
        let err = validate(&mut spec, &sample_schema(), &[]).unwrap_err();
        assert!(err.contains("maximum depth"));
    }
}
