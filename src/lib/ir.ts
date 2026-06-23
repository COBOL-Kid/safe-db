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

export type FilterOp = 'Eq' | 'Ne' | 'Gt' | 'Gte' | 'Lt' | 'Lte' | 'Like' | 'IsNull' | 'IsNotNull';

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

export interface FilterSpec {
	table_alias: string;
	column: string;
	op: FilterOp;
	value: string | null;
}

export interface QuerySpec {
	tables: TableRef[];
	columns: ColumnSel[];
	joins: JoinSpec[];
	filters: FilterSpec[];
	limit: number;
}

export interface QueryResult {
	columns: string[];
	rows: JsonValue[][];
	row_count: number;
	truncated: boolean;
	warnings: string[];
}

export type JsonValue = string | number | boolean | null | { [key: string]: JsonValue } | JsonValue[];

export const FILTER_OPS: { value: FilterOp; label: string }[] = [
	{ value: 'Eq', label: '=' },
	{ value: 'Ne', label: '≠' },
	{ value: 'Gt', label: '>' },
	{ value: 'Gte', label: '≥' },
	{ value: 'Lt', label: '<' },
	{ value: 'Lte', label: '≤' },
	{ value: 'Like', label: 'like' },
	{ value: 'IsNull', label: 'is null' },
	{ value: 'IsNotNull', label: 'is not null' }
];

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
