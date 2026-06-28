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
import { DEFAULT_LIMIT, defaultFilterGroup, newNodeId } from '$lib/ir';
import { columnKey, columnKeyPrefix, parseColumnKey } from '$lib/column-keys';
import { COST_GUARD_PREFIX, parseLimit } from '$lib/limits';
import * as api from '$lib/api';

export interface CanvasTable {
	tableInfo: TableInfo;
	alias: string;
	x: number;
	y: number;
}

/** Filter spec as supplied to the store's mutators. `id` is optional at this
 *  layer because the store assigns a stable UUID on insertion; persisted
 *  specs (and round-trips through `setFilters`) carry the ID explicitly. */
export type NewFilterSpec = Omit<FilterSpec, 'id'> & { id?: string };

class QueryStore {
	private aliasCounter = 0;

	tables = $state<CanvasTable[]>([]);
	selectedColumns = $state<Set<string>>(new Set());
	joins = $state<JoinSpec[]>([]);
	filters = $state<FilterGroup>(defaultFilterGroup());
	limit = $state(DEFAULT_LIMIT);

	connectorOverrides = $state<Record<string, GroupConnector>>({});

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
			const { alias, column } = parseColumnKey(key);
			columns.push({ table_alias: alias, column });
		}

		return {
			tables,
			columns,
			joins: [...this.joins],
			filters: JSON.parse(JSON.stringify(this.filters)),
			limit: this.limit,
			schema_version: 3,
			connector_overrides: { ...this.connectorOverrides }
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
			[...this.selectedColumns].filter((k) => !k.startsWith(columnKeyPrefix(alias)))
		);
		this.joins = this.joins.filter(
			(j) => j.left_alias !== alias && j.right_alias !== alias
		);
		this.filters = pruneFiltersReferencingAlias(this.filters, alias);
		this.connectorOverrides = rebuildOverrides(this.filters, this.connectorOverrides);
	}

	moveTable(alias: string, x: number, y: number) {
		this.tables = this.tables.map((t) =>
			t.alias === alias ? { ...t, x, y } : t
		);
	}

	toggleColumn(alias: string, column: string) {
		const key = columnKey(alias, column);
		const next = new Set(this.selectedColumns);
		if (next.has(key)) {
			next.delete(key);
		} else {
			next.add(key);
		}
		this.selectedColumns = next;
	}

	isColumnSelected(alias: string, column: string): boolean {
		return this.selectedColumns.has(columnKey(alias, column));
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

	addFilter(spec: NewFilterSpec) {
		this.filters = addLeafToGroup(this.filters, [], spec);
		this.connectorOverrides = rebuildOverrides(this.filters, this.connectorOverrides);
	}

	setFilters(group: FilterGroup) {
		this.filters = ensureGroupIds(group);
		this.connectorOverrides = rebuildOverrides(this.filters, this.connectorOverrides);
	}

	addFilterToGroup(groupPath: number[], spec: NewFilterSpec) {
		this.filters = addLeafToGroup(this.filters, groupPath, spec);
		this.connectorOverrides = rebuildOverrides(this.filters, this.connectorOverrides);
	}

	addGroupToGroup(groupPath: number[], connector: GroupConnector) {
		this.filters = addGroupToGroup(this.filters, groupPath, connector);
		this.connectorOverrides = rebuildOverrides(this.filters, this.connectorOverrides);
	}

	updateFilter(path: number[], spec: NewFilterSpec) {
		const existingId = getLeafIdAtPath(this.filters, path);
		const specWithId: FilterSpec = { ...spec, id: existingId || spec.id || newNodeId() };
		this.filters = updateNodeAtPath(this.filters, path, { Leaf: specWithId });
		this.connectorOverrides = rebuildOverrides(this.filters, this.connectorOverrides);
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
		this.connectorOverrides = rebuildOverrides(
			this.filters,
			this.connectorOverrides,
			path
		);
	}

	setConnectorOverrides(map: Record<string, GroupConnector>) {
		this.connectorOverrides = rebuildOverrides(this.filters, map);
	}

	/** Look up the override key for a child at the given number path. Returns
	 *  the child's stable `id` if the path resolves, or `null` if it doesn't
	 *  (out of range, path points to a leaf where a group was expected, etc.).
	 *  The backend compiler uses the same keying scheme: `connector_overrides`
	 *  is `Record<childId, GroupConnector>`. */
	pathKey(path: number[]): string | null {
		return childIdAtPath(this.filters, path);
	}

	getConnectorForChild(path: number[]): GroupConnector {
		if (path.length === 0) return this.filters.connector;
		const key = this.pathKey(path);
		if (key && this.connectorOverrides[key]) return this.connectorOverrides[key];
		const parentPath = path.slice(0, -1);
		const parent = getGroupAtPath(this.filters, parentPath);
		return parent ? parent.connector : 'And';
	}

	setChildConnector(path: number[], connector: GroupConnector) {
		// The first child of any group has no preceding sibling to join with,
		// so any override would never be honored by the compiler — drop it
		// rather than store dead state.
		if (path.length === 0 || path[path.length - 1] === 0) return;
		const key = this.pathKey(path);
		if (!key) return;
		const parent = getGroupAtPath(this.filters, path.slice(0, -1));
		const groupDefault = parent ? parent.connector : this.filters.connector;
		const next = { ...this.connectorOverrides };
		// Drop the override when it would match the parent group connector —
		// the override is then a redundant shadow that masks later group
		// connector changes from this child.
		if (connector === groupDefault) {
			delete next[key];
		} else {
			next[key] = connector;
		}
		this.connectorOverrides = next;
	}

	toggleChildConnector(path: number[]) {
		const current = this.getConnectorForChild(path);
		this.setChildConnector(path, current === 'And' ? 'Or' : 'And');
	}

	removeFilterNode(path: number[]) {
		if (path.length === 0) return;
		this.filters = removeNodeAtPath(this.filters, path);
		this.connectorOverrides = rebuildOverrides(this.filters, this.connectorOverrides);
	}

	setLimit(limit: number) {
		this.limit = parseLimit(limit);
	}

	pendingCostGuard = $state(false);
	hydrationWarning = $state<string | null>(null);

	async run(connectionId: string, force = false) {
		if (!this.canRun) return;
		this.running = true;
		this.error = null;
		this.results = null;
		this.pendingCostGuard = false;
		try {
			this.results = await api.runQuery(connectionId, this.spec, force);
		} catch (e) {
			const message = String(e);
			if (!force && message.startsWith(COST_GUARD_PREFIX)) {
				this.pendingCostGuard = true;
				this.error = message.slice(COST_GUARD_PREFIX.length);
			} else {
				this.error = message;
			}
		} finally {
			this.running = false;
		}
	}

	async runForced(connectionId: string) {
		await this.run(connectionId, true);
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
		this.pendingCostGuard = false;
		this.hydrationWarning = null;
		this.aliasCounter = 0;
		this.connectorOverrides = {};
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
	spec: NewFilterSpec
): FilterGroup {
	const specWithId: FilterSpec = { ...spec, id: spec.id ?? newNodeId() };
	if (path.length === 0) {
		return {
			...group,
			children: [...group.children, { Leaf: specWithId }]
		};
	}
	const [head, ...rest] = path;
	const child = group.children[head];
	if (child && 'Group' in child) {
		const newChildren = [...group.children];
		newChildren[head] = { Group: addLeafToGroup(child.Group, rest, specWithId) };
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
				{ Group: { id: newNodeId(), connector, children: [] } }
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
		const idx = path[0];
		if (idx < 0 || idx >= group.children.length) return group;
		const newChildren = [...group.children];
		newChildren[idx] = newNode;
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

/** Return the `id` of the node at the given number path, or `null` if the
 *  path doesn't resolve. The root group is the implicit parent of all paths
 *  (`childIdAtPath(group, [])` would error; callers should treat an empty
 *  path as a request for the root group itself, which is rarely needed). */
function childIdAtPath(group: FilterGroup, path: number[]): string | null {
	if (path.length === 0) return group.id || null;
	const [head, ...rest] = path;
	const child = group.children[head];
	if (!child) return null;
	if ('Leaf' in child) {
		return rest.length === 0 ? (child.Leaf.id || null) : null;
	}
	return childIdAtPath(child.Group, rest);
}

/** Return the `id` of a leaf at the given path, or `null` if the path
 *  doesn't resolve to a leaf. Used by `updateFilter` to preserve the leaf's
 *  identity when its value is edited. */
function getLeafIdAtPath(group: FilterGroup, path: number[]): string | null {
	if (path.length === 0) return null;
	const [head, ...rest] = path;
	const child = group.children[head];
	if (!child) return null;
	if ('Leaf' in child) {
		return rest.length === 0 ? (child.Leaf.id || null) : null;
	}
	return getLeafIdAtPath(child.Group, rest);
}

/** Recursively assign an `id` to any filter node that lacks one. Used by
 *  `setFilters` to upgrade specs from older builds (or external sources)
 *  that didn't carry IDs. */
function ensureGroupIds(group: FilterGroup): FilterGroup {
	const out: FilterGroup = {
		...group,
		id: group.id || newNodeId(),
		children: group.children.map((child) => {
			if ('Leaf' in child) {
				return { Leaf: child.Leaf.id ? child.Leaf : { ...child.Leaf, id: newNodeId() } };
			}
			return { Group: ensureGroupIds(child.Group) };
		})
	};
	return out;
}

/** Walk the filter tree and return only the override entries whose `id`
 *  still resolves to an existing child. Called after every tree mutation so
 *  that orphan overrides (e.g. after removing the bound child) are dropped
 *  from this map. Because keys are stable child IDs, sibling insertions
 *  and removals cannot silently rebind an override to a different child.
 *
 *  If `modifiedGroupPath` is provided, also drop entries whose parent group
 *  is the modified group and whose value matches the group's new connector —
 *  those overrides just became redundant shadows that would mask later
 *  group flips. The prune and rebuild happen in a single tree walk. */
function rebuildOverrides(
	group: FilterGroup,
	overrides: Record<string, GroupConnector>,
	modifiedGroupPath: number[] | null = null
): Record<string, GroupConnector> {
	const ids = new Set<string>();
	const parentMap = new Map<string, FilterGroup>();

	function walk(g: FilterGroup) {
		if (g.id) ids.add(g.id);
		for (const child of g.children) {
			if ('Leaf' in child) {
				if (child.Leaf.id) {
					ids.add(child.Leaf.id);
					parentMap.set(child.Leaf.id, g);
				}
			} else {
				if (child.Group.id) {
					ids.add(child.Group.id);
					parentMap.set(child.Group.id, g);
				}
				walk(child.Group);
			}
		}
	}
	walk(group);

	const modifiedGroup = modifiedGroupPath
		? getGroupAtPath(group, modifiedGroupPath)
		: null;
	const pruneByParent = modifiedGroup !== null;

	const next: Record<string, GroupConnector> = {};
	for (const [key, value] of Object.entries(overrides)) {
		if (!ids.has(key)) continue;
		if (pruneByParent) {
			const parent = parentMap.get(key);
			if (parent && parent.id === modifiedGroup.id && parent.connector === value) {
				continue;
			}
		}
		next[key] = value;
	}
	return next;
}

export { QueryStore };
export const query = new QueryStore();
