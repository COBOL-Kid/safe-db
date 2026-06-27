export type Dialect = 'Postgres' | 'MySql' | 'Mssql' | 'Oracle';

export interface ConnectionDef {
	id: string;
	name: string;
	dialect: Dialect;
	host: string;
	port: number;
	database: string;
	username: string;
}

export interface ColumnInfo {
	name: string;
	data_type: string;
	nullable: boolean;
	is_indexed: boolean;
}

export interface IndexInfo {
	name: string;
	columns: string[];
	is_unique: boolean;
	is_primary: boolean;
}

export interface TableInfo {
	schema: string;
	name: string;
	columns: ColumnInfo[];
	indexes: IndexInfo[];
}

export interface Schema {
	tables: TableInfo[];
}

export interface ConnectionWithSchema {
	def: ConnectionDef;
	schema: Schema | null;
}

export const DIALECTS: { value: Dialect; label: string; defaultPort: number }[] = [
	{ value: 'Postgres', label: 'PostgreSQL', defaultPort: 5432 },
	{ value: 'MySql', label: 'MySQL', defaultPort: 3306 },
	{ value: 'Mssql', label: 'SQL Server', defaultPort: 1433 },
	{ value: 'Oracle', label: 'Oracle', defaultPort: 1521 }
];

export function qualifiedName(table: TableInfo): string {
	return table.schema ? `${table.schema}.${table.name}` : table.name;
}

export const CURRENT_SCHEMA_VERSION = 2;

export type FilterOp =
	| 'Eq'
	| 'Ne'
	| 'Gt'
	| 'Gte'
	| 'Lt'
	| 'Lte'
	| 'Like'
	| 'NotLike'
	| 'Ilike'
	| 'In'
	| 'NotIn'
	| 'Between'
	| 'IsNull'
	| 'IsNotNull'
	| 'IsEmpty'
	| 'IsNotEmpty';

export type GroupConnector = 'And' | 'Or';

export type LiteralKind = 'Text' | 'Int' | 'Float' | 'Bool' | 'Date' | 'DateTime';

export interface TableRef {
	schema: string;
	name: string;
	alias: string;
}

export interface ColumnSel {
	table_alias: string;
	column: string;
}

export interface JoinSpec {
	left_alias: string;
	left_column: string;
	right_alias: string;
	right_column: string;
}

export interface FilterLiteral {
	kind: LiteralKind;
	text: string;
}

export type FilterValue =
	| { Single: FilterLiteral }
	| { List: FilterLiteral[] }
	| { Pair: [FilterLiteral, FilterLiteral] };

export interface FilterSpec {
	table_alias: string;
	column: string;
	op: FilterOp;
	value: FilterValue | null;
}

export interface FilterGroup {
	connector: GroupConnector;
	children: FilterNode[];
}

export type FilterNode =
	| { Leaf: FilterSpec }
	| { Group: FilterGroup };

export interface QuerySpec {
	tables: TableRef[];
	columns: ColumnSel[];
	joins: JoinSpec[];
	filters: FilterGroup;
	limit: number;
	schema_version: number;
}

export interface QueryResult {
	columns: string[];
	rows: JsonValue[][];
	row_count: number;
	truncated: boolean;
	warnings: string[];
}

export type JsonValue = string | number | boolean | null | { [key: string]: JsonValue } | JsonValue[];

export type ValueKind = 'None' | 'Single' | 'List' | 'Pair';

export function valueKindForOp(op: FilterOp): ValueKind {
	switch (op) {
		case 'IsNull':
		case 'IsNotNull':
		case 'IsEmpty':
		case 'IsNotEmpty':
			return 'None';
		case 'In':
		case 'NotIn':
			return 'List';
		case 'Between':
			return 'Pair';
		default:
			return 'Single';
	}
}

export function needsValue(op: FilterOp): boolean {
	return valueKindForOp(op) !== 'None';
}

export const FILTER_OPS: { value: FilterOp; label: string }[] = [
	{ value: 'Eq', label: '=' },
	{ value: 'Ne', label: '≠' },
	{ value: 'Gt', label: '>' },
	{ value: 'Gte', label: '≥' },
	{ value: 'Lt', label: '<' },
	{ value: 'Lte', label: '≤' },
	{ value: 'Like', label: 'like' },
	{ value: 'NotLike', label: 'not like' },
	{ value: 'Ilike', label: 'ilike' },
	{ value: 'In', label: 'in' },
	{ value: 'NotIn', label: 'not in' },
	{ value: 'Between', label: 'between' },
	{ value: 'IsNull', label: 'is null' },
	{ value: 'IsNotNull', label: 'is not null' },
	{ value: 'IsEmpty', label: 'is empty' },
	{ value: 'IsNotEmpty', label: 'is not empty' }
];

export type ColumnCategory = 'Text' | 'Numeric' | 'Bool' | 'Date' | 'DateTime' | 'Other';

export function classifyColumn(dataType: string): ColumnCategory {
	const dt = dataType.toLowerCase();
	if (dt === 'bool' || dt === 'boolean' || dt === 'bit') return 'Bool';
	if (dt === 'date') return 'Date';
	if (dt.startsWith('timestamp') || dt.startsWith('datetime') || dt === 'datetime2' || dt === 'smalldatetime')
		return 'DateTime';
	if (
		[
			'int', 'integer', 'smallint', 'bigint', 'mediumint', 'tinyint',
			'serial', 'bigserial', 'decimal', 'numeric', 'real', 'double',
			'float', 'float4', 'float8', 'money', 'smallmoney', 'double precision'
		].includes(dt) ||
		dt.startsWith('decimal') ||
		dt.startsWith('numeric')
	)
		return 'Numeric';
	if (
		[
			'text', 'varchar', 'char', 'character', 'character varying', 'string',
			'tinytext', 'mediumtext', 'longtext', 'nvarchar', 'nchar',
			'varchar2', 'nvarchar2', 'clob', 'nclob', 'xml'
		].includes(dt) ||
		dt.startsWith('varchar') ||
		dt.startsWith('char') ||
		dt.startsWith('nchar') ||
		dt.startsWith('nvarchar')
	)
		return 'Text';
	return 'Other';
}

export function literalKindForColumn(dataType: string): LiteralKind {
	switch (classifyColumn(dataType)) {
		case 'Numeric':
			return 'Int';
		case 'Bool':
			return 'Bool';
		case 'Date':
			return 'Date';
		case 'DateTime':
			return 'DateTime';
		default:
			return 'Text';
	}
}

export function opsForColumn(dataType: string): FilterOp[] {
	switch (classifyColumn(dataType)) {
		case 'Text':
			return ['Eq', 'Ne', 'Like', 'NotLike', 'Ilike', 'In', 'NotIn', 'IsNull', 'IsNotNull', 'IsEmpty', 'IsNotEmpty'];
		case 'Numeric':
			return ['Eq', 'Ne', 'Gt', 'Gte', 'Lt', 'Lte', 'In', 'NotIn', 'Between', 'IsNull', 'IsNotNull'];
		case 'Bool':
			return ['Eq', 'Ne', 'IsNull', 'IsNotNull'];
		case 'Date':
		case 'DateTime':
			return ['Eq', 'Ne', 'Gt', 'Gte', 'Lt', 'Lte', 'Between', 'IsNull', 'IsNotNull'];
		default:
			return ['Eq', 'Ne', 'IsNull', 'IsNotNull'];
	}
}

export function opLabel(op: FilterOp): string {
	return FILTER_OPS.find((f) => f.value === op)?.label ?? op;
}

export function defaultFilterGroup(): FilterGroup {
	return { connector: 'And', children: [] };
}

export function makeLiteral(dataType: string, text: string): FilterLiteral {
	return { kind: literalKindForColumn(dataType), text };
}

export function makeFilter(
	tableAlias: string,
	column: string,
	dataType: string,
	op: FilterOp,
	text: string
): FilterSpec {
	const kind = literalKindForColumn(dataType);
	const vk = valueKindForOp(op);
	let value: FilterValue | null = null;
	if (vk === 'Single') {
		value = { Single: { kind, text } };
	} else if (vk === 'List') {
		value = { List: text ? [{ kind, text }] : [] };
	} else if (vk === 'Pair') {
		value = { Pair: [{ kind, text: '' }, { kind, text: '' }] };
	}
	return { table_alias: tableAlias, column, op, value };
}

export const MAX_LIMIT = 1000;
export const DEFAULT_LIMIT = 100;

export interface SavedQuery {
	id: string;
	name: string;
	connection_id: string;
	spec: QuerySpec;
	created_at: string;
}

export interface HistoryEntry {
	id: string;
	connection_id: string;
	connection_name: string;
	spec: QuerySpec;
	row_count: number;
	warnings: string[];
	error: Option<string>;
	timestamp: string;
}

type Option<T> = T | null;

export interface Settings {
	blocked_schemas: string[];
	explain_cost_threshold: number;
	theme: string;
}
