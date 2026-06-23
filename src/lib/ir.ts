export type Dialect = 'Postgres' | 'MySql';

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
	{ value: 'MySql', label: 'MySQL', defaultPort: 3306 }
];

export function qualifiedName(table: TableInfo): string {
	return table.schema ? `${table.schema}.${table.name}` : table.name;
}
