import type {
	ColumnSel,
	FilterGroup,
	FilterNode,
	FilterSpec,
	GroupConnector,
	JoinSpec,
	QueryResult,
	QuerySpec,
	TableInfo,
	TableRef
} from '$lib/ir';
import { DEFAULT_LIMIT, MAX_LIMIT, defaultFilterGroup } from '$lib/ir';
import * as api from '$lib/api';

export interface CanvasTable {
	tableInfo: TableInfo;
	alias: string;
	x: number;
	y: number;
}

class QueryStore {
	private aliasCounter = 0;

	tables = $state<CanvasTable[]>([]);
	selectedColumns = $state<Set<string>>(new Set());
	joins = $state<JoinSpec[]>([]);
	filters = $state<FilterGroup>(defaultFilterGroup());
	limit = $state(DEFAULT_LIMIT);

	results = $state<QueryResult | null>(null);
	running = $state(false);
	error = $state<string | null>(null);

	tableCount = $derived(this.tables.length);
	canRun = $derived(this.tables.length > 0 && !this.running);
	filterCount = $derived.by(() => {
		function countLeaves(group: FilterGroup): number {
			return group.children.reduce((sum, child) => {
				if ('Leaf' in child) return sum + 1;
				return sum + countLeaves(child.Group);
			}, 0);
		}
		return countLeaves(this.filters);
	});

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
			filters: JSON.parse(JSON.stringify(this.filters)),
			limit: this.limit,
			schema_version: 2
		};
	});

	addTable(tableInfo: TableInfo) {
		const alias = `t${this.aliasCounter++}`;
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
		this.filters = pruneFiltersReferencingAlias(this.filters, alias);
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

	addFilter(spec: FilterSpec) {
		this.filters = addLeafToGroup(this.filters, [], spec);
	}

	setFilters(group: FilterGroup) {
		this.filters = group;
	}

	addFilterToGroup(groupPath: number[], spec: FilterSpec) {
		this.filters = addLeafToGroup(this.filters, groupPath, spec);
	}

	addGroupToGroup(groupPath: number[], connector: GroupConnector) {
		this.filters = addGroupToGroup(this.filters, groupPath, connector);
	}

	updateFilter(path: number[], spec: FilterSpec) {
		this.filters = updateNodeAtPath(this.filters, path, { Leaf: spec });
	}

	setGroupConnector(path: number[], connector: GroupConnector) {
		if (path.length === 0) {
			this.filters = { ...this.filters, connector };
		} else {
			const group = getGroupAtPath(this.filters, path);
			if (group) {
				this.filters = updateNodeAtPath(this.filters, path, {
					Group: { ...group, connector }
				});
			}
		}
	}

	removeFilterNode(path: number[]) {
		if (path.length === 0) return;
		this.filters = removeNodeAtPath(this.filters, path);
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
		this.filters = defaultFilterGroup();
		this.limit = DEFAULT_LIMIT;
		this.results = null;
		this.error = null;
		this.running = false;
		this.aliasCounter = 0;
	}
}

function getGroupAtPath(group: FilterGroup, path: number[]): FilterGroup | null {
	if (path.length === 0) return group;
	const [head, ...rest] = path;
	const child = group.children[head];
	if (!child) return null;
	if ('Group' in child) {
		return rest.length === 0 ? child.Group : getGroupAtPath(child.Group, rest);
	}
	return null;
}

function addLeafToGroup(
	group: FilterGroup,
	path: number[],
	spec: FilterSpec
): FilterGroup {
	if (path.length === 0) {
		return {
			...group,
			children: [...group.children, { Leaf: spec }]
		};
	}
	const [head, ...rest] = path;
	const child = group.children[head];
	if (child && 'Group' in child) {
		const newChildren = [...group.children];
		newChildren[head] = { Group: addLeafToGroup(child.Group, rest, spec) };
		return { ...group, children: newChildren };
	}
	return group;
}

function addGroupToGroup(
	group: FilterGroup,
	path: number[],
	connector: GroupConnector
): FilterGroup {
	if (path.length === 0) {
		return {
			...group,
			children: [
				...group.children,
				{ Group: { connector, children: [] } }
			]
		};
	}
	const [head, ...rest] = path;
	const child = group.children[head];
	if (child && 'Group' in child) {
		const newChildren = [...group.children];
		newChildren[head] = { Group: addGroupToGroup(child.Group, rest, connector) };
		return { ...group, children: newChildren };
	}
	return group;
}

function updateNodeAtPath(
	group: FilterGroup,
	path: number[],
	newNode: FilterNode
): FilterGroup {
	if (path.length === 0) return group;
	if (path.length === 1) {
		const newChildren = [...group.children];
		newChildren[path[0]] = newNode;
		return { ...group, children: newChildren };
	}
	const [head, ...rest] = path;
	const child = group.children[head];
	if (child && 'Group' in child) {
		const newChildren = [...group.children];
		newChildren[head] = { Group: updateNodeAtPath(child.Group, rest, newNode) };
		return { ...group, children: newChildren };
	}
	return group;
}

function removeNodeAtPath(group: FilterGroup, path: number[]): FilterGroup {
	if (path.length === 0) return group;
	if (path.length === 1) {
		return {
			...group,
			children: group.children.filter((_, i) => i !== path[0])
		};
	}
	const [head, ...rest] = path;
	const child = group.children[head];
	if (child && 'Group' in child) {
		const newChildren = [...group.children];
		newChildren[head] = { Group: removeNodeAtPath(child.Group, rest) };
		return { ...group, children: newChildren };
	}
	return group;
}

function pruneFiltersReferencingAlias(group: FilterGroup, alias: string): FilterGroup {
	const children = group.children
		.map((child) => {
			if ('Leaf' in child) {
				return child.Leaf.table_alias === alias ? null : child;
			}
			const pruned = pruneFiltersReferencingAlias(child.Group, alias);
			return pruned.children.length === 0 ? null : { Group: pruned };
		})
		.filter((c): c is FilterNode => c !== null);
	return { ...group, children };
}

export { QueryStore };
export const query = new QueryStore();
