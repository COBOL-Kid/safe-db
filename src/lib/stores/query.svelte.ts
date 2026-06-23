import type {
	ColumnSel,
	FilterSpec,
	JoinSpec,
	QueryResult,
	QuerySpec,
	TableInfo,
	TableRef
} from '$lib/ir';
import { DEFAULT_LIMIT, MAX_LIMIT } from '$lib/ir';
import * as api from '$lib/api';

export interface CanvasTable {
	tableInfo: TableInfo;
	alias: string;
	x: number;
	y: number;
}

let aliasCounter = 0;

function nextAlias(): string {
	return `t${aliasCounter++}`;
}

class QueryStore {
	tables = $state<CanvasTable[]>([]);
	selectedColumns = $state<Set<string>>(new Set());
	joins = $state<JoinSpec[]>([]);
	filters = $state<FilterSpec[]>([]);
	limit = $state(DEFAULT_LIMIT);

	results = $state<QueryResult | null>(null);
	running = $state(false);
	error = $state<string | null>(null);

	tableCount = $derived(this.tables.length);
	canRun = $derived(this.tables.length > 0 && !this.running);

	spec = $derived.by<QuerySpec>(() => {
		const tables: TableRef[] = this.tables.map((t) => ({
			schema: t.tableInfo.schema,
			name: t.tableInfo.name,
			alias: t.alias
		}));

		const columns: ColumnSel[] = [];
		for (const key of this.selectedColumns) {
			const [alias, column] = key.split('.');
			columns.push({ table_alias: alias, column });
		}

		return {
			tables,
			columns,
			joins: [...this.joins],
			filters: [...this.filters],
			limit: this.limit
		};
	});

	addTable(tableInfo: TableInfo) {
		const alias = nextAlias();
		const offset = this.tables.length * 30;
		this.tables = [
			...this.tables,
			{
				tableInfo,
				alias,
				x: 40 + offset,
				y: 40 + offset
			}
		];
	}

	removeTable(alias: string) {
		this.tables = this.tables.filter((t) => t.alias !== alias);
		this.selectedColumns = new Set(
			[...this.selectedColumns].filter((k) => !k.startsWith(`${alias}.`))
		);
		this.joins = this.joins.filter(
			(j) => j.left_alias !== alias && j.right_alias !== alias
		);
		this.filters = this.filters.filter((f) => f.table_alias !== alias);
	}

	moveTable(alias: string, x: number, y: number) {
		this.tables = this.tables.map((t) =>
			t.alias === alias ? { ...t, x, y } : t
		);
	}

	toggleColumn(alias: string, column: string) {
		const key = `${alias}.${column}`;
		const next = new Set(this.selectedColumns);
		if (next.has(key)) {
			next.delete(key);
		} else {
			next.add(key);
		}
		this.selectedColumns = next;
	}

	isColumnSelected(alias: string, column: string): boolean {
		return this.selectedColumns.has(`${alias}.${column}`);
	}

	addJoin(join: JoinSpec) {
		const exists = this.joins.some(
			(j) =>
				(j.left_alias === join.left_alias &&
					j.left_column === join.left_column &&
					j.right_alias === join.right_alias &&
					j.right_column === join.right_column) ||
				(j.left_alias === join.right_alias &&
					j.left_column === join.right_column &&
					j.right_alias === join.left_alias &&
					j.right_column === join.left_column)
		);
		if (!exists) {
			this.joins = [...this.joins, join];
		}
	}

	removeJoin(index: number) {
		this.joins = this.joins.filter((_, i) => i !== index);
	}

	addFilter(filter: FilterSpec) {
		this.filters = [...this.filters, filter];
	}

	updateFilter(index: number, filter: FilterSpec) {
		this.filters = this.filters.map((f, i) => (i === index ? filter : f));
	}

	removeFilter(index: number) {
		this.filters = this.filters.filter((_, i) => i !== index);
	}

	setLimit(limit: number) {
		this.limit = Math.min(Math.max(1, limit), MAX_LIMIT);
	}

	async run(connectionId: string) {
		if (!this.canRun) return;
		this.running = true;
		this.error = null;
		this.results = null;
		try {
			this.results = await api.runQuery(connectionId, this.spec);
		} catch (e) {
			this.error = String(e);
		} finally {
			this.running = false;
		}
	}

	clear() {
		this.tables = [];
		this.selectedColumns = new Set();
		this.joins = [];
		this.filters = [];
		this.limit = DEFAULT_LIMIT;
		this.results = null;
		this.error = null;
		this.running = false;
		aliasCounter = 0;
	}
}

export const query = new QueryStore();
