import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryStore } from '$lib/stores/query.svelte';
import * as api from '$lib/api';
import { DEFAULT_LIMIT, MAX_LIMIT, type TableInfo } from '$lib/ir';
import { columnKey } from '$lib/column-keys';
import { COST_GUARD_PREFIX } from '$lib/limits';
import { CANVAS_CARD_HEIGHT, CANVAS_CARD_WIDTH } from '$lib/canvas-geometry';

vi.mock('$lib/api');

function makeTable(name: string, schema = 'public'): TableInfo {
	return {
		schema,
		name,
		columns: [
			{ name: 'id', data_type: 'int', nullable: false, is_indexed: true },
			{ name: 'name', data_type: 'text', nullable: true, is_indexed: false }
		],
		indexes: []
	};
}

describe('QueryStore', () => {
	let store: QueryStore;

	beforeEach(() => {
		store = new QueryStore();
	});

	it('assigns sequential aliases when adding tables', () => {
		store.addTable(makeTable('a'));
		store.addTable(makeTable('b'));
		expect(store.tables.map((t) => t.alias)).toEqual(['t0', 't1']);
	});

	it('assigns default canvas dimensions when adding tables', () => {
		store.addTable(makeTable('a'));
		expect(store.tables[0].width).toBe(CANVAS_CARD_WIDTH);
		expect(store.tables[0].height).toBe(CANVAS_CARD_HEIGHT);
	});

	it('does not include canvas dimensions in the query spec', () => {
		store.addTable(makeTable('a'));
		const alias = store.tables[0].alias;
		store.resizeTable(alias, 360, 420);

		expect(store.spec.tables[0]).toEqual({
			schema: 'public',
			name: 'a',
			alias
		});
		expect(store.spec.tables[0]).not.toHaveProperty('width');
		expect(store.spec.tables[0]).not.toHaveProperty('height');
	});

	it('removes table and cleans up columns, joins, and filters', () => {
		store.addTable(makeTable('a'));
		store.addTable(makeTable('b'));
		const aliasA = store.tables[0].alias;
		const aliasB = store.tables[1].alias;

		store.toggleColumn(aliasA, 'id');
		store.toggleColumn(aliasB, 'name');
		store.addJoin({
			left_alias: aliasA,
			left_column: 'id',
			right_alias: aliasB,
			right_column: 'id'
		});
		store.addFilter({
			table_alias: aliasA,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'x' } }
		});

		store.removeTable(aliasA);

		expect(store.tables).toHaveLength(1);
		expect(store.selectedColumns.size).toBe(1);
		expect([...store.selectedColumns][0]).toBe(columnKey(aliasB, 'name'));
		expect(store.joins).toHaveLength(0);
		expect(store.filters.children).toHaveLength(0);
	});

	it('deduplicates joins in both directions', () => {
		store.addTable(makeTable('a'));
		store.addTable(makeTable('b'));
		const [a, b] = store.tables.map((t) => t.alias);
		const join = {
			left_alias: a,
			left_column: 'id',
			right_alias: b,
			right_column: 'id'
		};
		store.addJoin(join);
		store.addJoin({
			left_alias: b,
			left_column: 'id',
			right_alias: a,
			right_column: 'id'
		});
		expect(store.joins).toHaveLength(1);
	});

	it('spec encodes columns with dots in the name', () => {
		const table: TableInfo = {
			schema: 'public',
			name: 'meta',
			columns: [
				{ name: 'foo.bar', data_type: 'text', nullable: true, is_indexed: false }
			],
			indexes: []
		};
		store.addTable(table);
		const alias = store.tables[0].alias;
		store.toggleColumn(alias, 'foo.bar');
		expect(store.spec.columns).toEqual([{ table_alias: alias, column: 'foo.bar' }]);
	});

	it('clamps limit between 1 and MAX_LIMIT', () => {
		store.setLimit(0);
		expect(store.limit).toBe(1);
		store.setLimit(9999);
		expect(store.limit).toBe(9999);
		store.setLimit(10001);
		expect(store.limit).toBe(MAX_LIMIT);
	});

	it('builds spec from current state', () => {
		store.addTable(makeTable('products'));
		const alias = store.tables[0].alias;
		store.toggleColumn(alias, 'id');
		store.setLimit(50);

		const spec = store.spec;
		// The root group carries a generated UUID that we can't predict; assert
		// the rest of the spec and then check the id is present and non-empty.
		expect(typeof spec.filters.id).toBe('string');
		expect(spec.filters.id).toBeTruthy();
		expect({
			tables: spec.tables,
			columns: spec.columns,
			joins: spec.joins,
			filters: { connector: spec.filters.connector, children: spec.filters.children },
			limit: spec.limit,
			schema_version: spec.schema_version,
			connector_overrides: spec.connector_overrides
		}).toEqual({
			tables: [{ schema: 'public', name: 'products', alias }],
			columns: [{ table_alias: alias, column: 'id' }],
			joins: [],
			filters: { connector: 'And', children: [] },
			limit: 50,
			schema_version: 3,
			connector_overrides: {}
		});
	});

	it('clear resets all state', () => {
		store.addTable(makeTable('a'));
		store.toggleColumn(store.tables[0].alias, 'id');
		store.clear();
		expect(store.tables).toHaveLength(0);
		expect(store.selectedColumns.size).toBe(0);
		expect(store.limit).toBe(100);
	});

	it('adds a leaf filter to the root group', () => {
		store.addTable(makeTable('a'));
		const alias = store.tables[0].alias;
		store.addFilter({
			table_alias: alias,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'widget' } }
		});
		expect(store.filters.children).toHaveLength(1);
	});

	it('adds a nested group to the root group', () => {
		store.addTable(makeTable('a'));
		store.addGroupToGroup([], 'Or');
		expect(store.filters.children).toHaveLength(1);
		const child = store.filters.children[0];
		expect('Group' in child).toBe(true);
		if ('Group' in child) {
			expect(child.Group.connector).toBe('Or');
			expect(child.Group.children).toHaveLength(0);
		}
	});

	it('adds a leaf to a nested group via path', () => {
		store.addTable(makeTable('a'));
		const alias = store.tables[0].alias;
		store.addGroupToGroup([], 'Or');
		store.addFilterToGroup([0], {
			table_alias: alias,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '42' } }
		});
		const child = store.filters.children[0];
		if ('Group' in child) {
			expect(child.Group.children).toHaveLength(1);
		}
	});

	it('removes a node by path', () => {
		store.addTable(makeTable('a'));
		const alias = store.tables[0].alias;
		store.addFilter({
			table_alias: alias,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'x' } }
		});
		store.addFilter({
			table_alias: alias,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '1' } }
		});
		expect(store.filters.children).toHaveLength(2);
		store.removeFilterNode([0]);
		expect(store.filters.children).toHaveLength(1);
	});

	it('toggles group connector', () => {
		store.setGroupConnector([], 'Or');
		expect(store.filters.connector).toBe('Or');
		store.setGroupConnector([], 'And');
		expect(store.filters.connector).toBe('And');
	});

	it('prunes filters referencing a removed table', () => {
		store.addTable(makeTable('a'));
		store.addTable(makeTable('b'));
		const aliasA = store.tables[0].alias;
		const aliasB = store.tables[1].alias;

		store.addFilter({
			table_alias: aliasA,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'x' } }
		});
		store.addFilter({
			table_alias: aliasB,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'y' } }
		});

		store.removeTable(aliasA);
		expect(store.filters.children).toHaveLength(1);
		const remaining = store.filters.children[0];
		if ('Leaf' in remaining) {
			expect(remaining.Leaf.table_alias).toBe(aliasB);
		}
	});

	it('drops nested groups that become empty after pruning', () => {
		store.addTable(makeTable('a'));
		store.addTable(makeTable('b'));
		const aliasA = store.tables[0].alias;
		const aliasB = store.tables[1].alias;

		// Root: [group(Or: [leaf on A, leaf on A]), leaf on B]
		store.addGroupToGroup([], 'Or');
		store.addFilterToGroup([0], {
			table_alias: aliasA,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'a1' } }
		});
		store.addFilterToGroup([0], {
			table_alias: aliasA,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '1' } }
		});
		store.addFilter({
			table_alias: aliasB,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'b1' } }
		});

		store.removeTable(aliasA);

		// The nested group only referenced table A, so it is pruned entirely;
		// only the leaf on B remains at the root.
		expect(store.filters.children).toHaveLength(1);
		const remaining = store.filters.children[0];
		expect('Leaf' in remaining).toBe(true);
		if ('Leaf' in remaining) {
			expect(remaining.Leaf.table_alias).toBe(aliasB);
		}
	});

	it('ignores filter updates at a stale out-of-bounds path', () => {
		store.addTable(makeTable('a'));
		const alias = store.tables[0].alias;
		store.addFilter({
			table_alias: alias,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'x' } }
		});

		store.updateFilter([99], {
			table_alias: alias,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'y' } }
		});

		// No sparse-array holes are created; the existing filter is unchanged.
		expect(store.filters.children).toHaveLength(1);
		const leaf = store.filters.children[0];
		if ('Leaf' in leaf) {
			expect(leaf.Leaf.value).toEqual({ Single: { kind: 'Text', text: 'x' } });
		}
	});

	it('tracks and toggles per-child connectors without mutating FilterGroup IR', () => {
		store.addTable(makeTable('a'));
		const alias = store.tables[0].alias;
		store.addFilter({
			table_alias: alias,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '1' } }
		});
		store.addFilter({
			table_alias: alias,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'x' } }
		});

		expect(store.getConnectorForChild([1])).toBe('And');
		store.toggleChildConnector([1]);
		expect(store.getConnectorForChild([1])).toBe('Or');
		// IR shape is unchanged — overrides are stored in a separate map.
		expect(store.filters.connector).toBe('And');
		expect(store.filters.children).toHaveLength(2);
	});

	it('emits connector_overrides in spec', () => {
		store.addTable(makeTable('a'));
		const alias = store.tables[0].alias;
		store.addFilter({
			table_alias: alias,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '1' } }
		});
		store.addFilter({
			table_alias: alias,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'x' } }
		});
		store.toggleChildConnector([1]);

		const key = store.pathKey([1]);
		expect(store.spec.connector_overrides).toEqual({ [key!]: 'Or' });
	});

	it('drops an override when the bound child is removed', () => {
		store.addTable(makeTable('a'));
		const alias = store.tables[0].alias;
		store.addFilter({
			table_alias: alias,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '1' } }
		});
		store.addFilter({
			table_alias: alias,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'x' } }
		});
		store.toggleChildConnector([1]);
		const key = store.pathKey([1]);
		expect(store.connectorOverrides).toEqual({ [key!]: 'Or' });

		// Removing the bound child (index 1) orphans the override, which must
		// be pruned from the map.
		store.removeFilterNode([1]);
		expect(store.filters.children).toHaveLength(1);
		expect(store.connectorOverrides).toEqual({});
	});

	it('preserves an override when a sibling is removed (bound child survives)', () => {
		store.addTable(makeTable('a'));
		const alias = store.tables[0].alias;
		store.addFilter({
			table_alias: alias,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '1' } }
		});
		store.addFilter({
			table_alias: alias,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'x' } }
		});
		store.addFilter({
			table_alias: alias,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'y' } }
		});
		store.toggleChildConnector([2]);
		const key = store.pathKey([2]);
		expect(store.connectorOverrides).toEqual({ [key!]: 'Or' });

		// Removing the first sibling shifts the bound child from index 2 to
		// index 1, but the override follows the child by id, not by position.
		store.removeFilterNode([0]);
		expect(store.connectorOverrides).toEqual({ [key!]: 'Or' });
	});

	it('preserves an override when a sibling is appended after the bound child', () => {
		// Regression for the previous positional-keying design, where
		// mutating the sibling set could silently rebind an override to a
		// different child. With id-keyed overrides, the binding follows the
		// child regardless of how many siblings are added or where.
		store.addTable(makeTable('a'));
		const alias = store.tables[0].alias;
		store.addFilter({
			table_alias: alias,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '1' } }
		});
		store.addFilter({
			table_alias: alias,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'x' } }
		});
		store.addFilter({
			table_alias: alias,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'y' } }
		});
		store.toggleChildConnector([2]);
		const key = store.pathKey([2]);
		expect(store.connectorOverrides).toEqual({ [key!]: 'Or' });

		// Append a fourth leaf; the bound child stays at index 2 and the
		// override follows it by id.
		store.addFilter({
			table_alias: alias,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'z' } }
		});
		expect(store.filters.children).toHaveLength(4);
		expect(store.connectorOverrides).toEqual({ [key!]: 'Or' });
	});

	it('drops an override whose target child is pruned with its table', () => {
		store.addTable(makeTable('a'));
		store.addTable(makeTable('b'));
		const aliasA = store.tables[0].alias;
		const aliasB = store.tables[1].alias;
		store.addFilter({
			table_alias: aliasA,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '1' } }
		});
		store.addFilter({
			table_alias: aliasB,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'x' } }
		});
		store.toggleChildConnector([1]);
		const key = store.pathKey([1]);
		expect(store.connectorOverrides).toEqual({ [key!]: 'Or' });

		// Removing table B also removes the bound leaf; the override is an
		// orphan and must be dropped.
		store.removeTable(aliasB);
		expect(store.connectorOverrides).toEqual({});
	});

	it('drops overrides for children inside a pruned empty nested group', () => {
		store.addTable(makeTable('a'));
		store.addTable(makeTable('b'));
		const aliasA = store.tables[0].alias;
		const aliasB = store.tables[1].alias;
		store.addGroupToGroup([], 'Or');
		store.addFilterToGroup([0], {
			table_alias: aliasA,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '1' } }
		});
		store.addFilterToGroup([0], {
			table_alias: aliasA,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'x' } }
		});
		store.addFilter({
			table_alias: aliasB,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'b' } }
		});
		// Override on the second child of the nested group at path [0, 1].
		// Group connector is Or, so set And explicitly (toggle would also land on And).
		store.setChildConnector([0, 1], 'And');
		const nestedKey = store.pathKey([0, 1]);
		expect(store.connectorOverrides).toEqual({ [nestedKey!]: 'And' });

		// Removing table A deletes both nested leaves; the empty nested group
		// is dropped entirely, so the override must be pruned.
		store.removeTable(aliasA);
		expect(store.connectorOverrides).toEqual({});
	});

	it('setChildConnector drops the override when it matches the group default', () => {
		store.addTable(makeTable('a'));
		const alias = store.tables[0].alias;
		store.addFilter({
			table_alias: alias,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '1' } }
		});
		store.addFilter({
			table_alias: alias,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'x' } }
		});
		// Group is And; override of And is redundant and must be cleared.
		store.setChildConnector([1], 'And');
		expect(store.connectorOverrides).toEqual({});

		// An override that differs from the group default is preserved.
		store.setChildConnector([1], 'Or');
		const key = store.pathKey([1]);
		expect(store.connectorOverrides).toEqual({ [key!]: 'Or' });

		// Flipping the group connector to Or makes the existing Or override
		// redundant; setGroupConnector should drop it instead of leaving a
		// shadow.
		store.setGroupConnector([], 'Or');
		expect(store.connectorOverrides).toEqual({});
	});

	it('setChildConnector is a no-op for the first child of any group', () => {
		store.addTable(makeTable('a'));
		const alias = store.tables[0].alias;
		store.addFilter({
			table_alias: alias,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '1' } }
		});
		store.addFilter({
			table_alias: alias,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'x' } }
		});
		store.setChildConnector([0], 'Or');
		expect(store.connectorOverrides).toEqual({});
	});

	it('setGroupConnector drops redundant overrides at the modified path only', () => {
		store.addTable(makeTable('a'));
		const alias = store.tables[0].alias;
		// Actual root: [leaf1, [group(And): []], leaf4]
		// After addFilterToGroup the nested group is at index 1, not 0.
		store.addFilter({
			table_alias: alias,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '1' } }
		});
		store.addGroupToGroup([], 'And');
		store.addFilterToGroup([1], {
			table_alias: alias,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '2' } }
		});
		store.addFilterToGroup([1], {
			table_alias: alias,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '3' } }
		});
		store.addFilter({
			table_alias: alias,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'y' } }
		});
		// Override on the second child of the nested group (parent path [1]).
		store.setChildConnector([1, 1], 'Or');
		// Override on the third child of the root group (parent path []).
		store.setChildConnector([2], 'Or');
		const nestedKey = store.pathKey([1, 1]);
		const rootKey = store.pathKey([2]);
		expect(store.connectorOverrides).toEqual({ [nestedKey!]: 'Or', [rootKey!]: 'Or' });

		// Flipping the nested group (path [1]) to Or makes the nested
		// override redundant; the root-scoped override must survive.
		store.setGroupConnector([1], 'Or');
		expect(store.connectorOverrides).toEqual({ [rootKey!]: 'Or' });
	});

	it('setConnectorOverrides restores and prunes entries against the current tree', () => {
		store.addTable(makeTable('a'));
		const alias = store.tables[0].alias;
		store.addFilter({
			table_alias: alias,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '1' } }
		});
		store.addFilter({
			table_alias: alias,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'x' } }
		});
		const key = store.pathKey([1]);
		store.setConnectorOverrides({ [key!]: 'Or', '9.9': 'And' });
		expect(store.connectorOverrides).toEqual({ [key!]: 'Or' });
	});

	it('clear() resets per-child connector overrides', () => {
		store.addTable(makeTable('a'));
		const alias = store.tables[0].alias;
		store.addFilter({
			table_alias: alias,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '1' } }
		});
		store.addFilter({
			table_alias: alias,
			column: 'name',
			op: 'Eq',
			value: { Single: { kind: 'Text', text: 'x' } }
		});
		store.toggleChildConnector([1]);
		const key = store.pathKey([1]);
		expect(store.connectorOverrides).toEqual({ [key!]: 'Or' });

		store.clear();
		expect(store.connectorOverrides).toEqual({});
	});

	describe('legacy empty-id specs', () => {
		it('pathKey returns null for a leaf whose id is the empty string', () => {
			// Simulate a legacy spec that carried an empty-string id on a leaf.
			store.addTable(makeTable('a'));
			const alias = store.tables[0].alias;
			store.filters = {
				id: 'root',
				connector: 'And',
				children: [
					{
						Leaf: {
							id: '',
							table_alias: alias,
							column: 'name',
							op: 'Eq',
							value: { Single: { kind: 'Text', text: 'x' } }
						}
					}
				]
			};
			expect(store.pathKey([0])).toBeNull();
		});

		it('updateFilter regenerates the leaf id when the existing leaf has an empty id', () => {
			store.addTable(makeTable('a'));
			const alias = store.tables[0].alias;
			store.filters = {
				id: 'root',
				connector: 'And',
				children: [
					{
						Leaf: {
							id: '',
							table_alias: alias,
							column: 'name',
							op: 'Eq',
							value: { Single: { kind: 'Text', text: 'x' } }
						}
					}
				]
			};

			store.updateFilter([0], {
				table_alias: alias,
				column: 'name',
				op: 'Eq',
				value: { Single: { kind: 'Text', text: 'y' } }
			});

			const leaf = store.filters.children[0];
			if ('Leaf' in leaf) {
				expect(leaf.Leaf.id).toBeTruthy();
				expect(leaf.Leaf.id).not.toBe('');
			}
		});

		it('setFilters drops orphan overrides keyed on empty-string ids', () => {
			// A legacy spec that explicitly carried id: "" on a leaf and an
			// override on that same empty key. The override must be pruned:
			// the new id assigned by ensureGroupIds doesn't match the
			// legacy key, and the empty key is not a valid node id.
			store.setFilters({
				id: 'root',
				connector: 'And',
				children: [
					{
						Leaf: {
							id: '',
							table_alias: 't0',
							column: 'name',
							op: 'Eq',
							value: { Single: { kind: 'Text', text: 'x' } }
						}
					}
				]
			});
			store.setConnectorOverrides({ '': 'Or' });
			expect(store.connectorOverrides).toEqual({});
		});
	});

	describe('run()', () => {
		beforeEach(() => {
			vi.mocked(api.runQuery).mockReset();
		});

		it('is a no-op when canRun is false (no tables)', async () => {
			await store.run('c1');
			expect(api.runQuery).not.toHaveBeenCalled();
			expect(store.running).toBe(false);
			expect(store.error).toBeNull();
		});

		it('is a no-op when canRun is false (running)', async () => {
			store.addTable(makeTable('a'));
			store.running = true;
			await store.run('c1');
			expect(api.runQuery).not.toHaveBeenCalled();
			store.running = false;
		});

		it('calls api.runQuery and clears running on success', async () => {
			store.addTable(makeTable('a'));
			vi.mocked(api.runQuery).mockResolvedValue({
				columns: [],
				rows: [],
				row_count: 0,
				truncated: false,
				warnings: []
			});

			await store.run('c1');

			expect(api.runQuery).toHaveBeenCalledOnce();
			expect(store.running).toBe(false);
			expect(store.error).toBeNull();
			expect(store.results?.row_count).toBe(0);
		});

		it('captures the error and clears running on failure', async () => {
			store.addTable(makeTable('a'));
			vi.mocked(api.runQuery).mockRejectedValue(new Error('boom'));

			await store.run('c1');

			expect(store.running).toBe(false);
			expect(store.error).toBe('Error: boom');
			expect(store.results).toBeNull();
		});

		it('sets pendingCostGuard when backend blocks with cost guard prefix', async () => {
			store.addTable(makeTable('a'));
			vi.mocked(api.runQuery).mockRejectedValue(
				`${COST_GUARD_PREFIX}EXPLAIN failed. Confirm to run this query anyway.`
			);

			await store.run('c1');

			expect(store.pendingCostGuard).toBe(true);
			expect(store.error).toContain('EXPLAIN failed');
		});
	});

	describe('spec deep-clone', () => {
		it('mutating the returned spec does not affect the store state', () => {
			store.addTable(makeTable('a'));
			store.setLimit(25);
			store.addFilter({
				table_alias: store.tables[0].alias,
				column: 'name',
				op: 'Eq',
				value: { Single: { kind: 'Text', text: 'x' } }
			});

			const spec = store.spec;
			// Mutate the returned spec — both primitive fields and deep fields.
			spec.limit = 999;
			if ('Leaf' in spec.filters.children[0]) {
				spec.filters.children[0].Leaf.value = { Single: { kind: 'Text', text: 'MUTATED' } };
			}
			(spec.tables[0] as { name: string }).name = 'tampered';

			// The store's internal state is unchanged.
			expect(store.limit).toBe(25);
			const leaf = store.filters.children[0];
			if ('Leaf' in leaf) {
				expect(leaf.Leaf.value).toEqual({ Single: { kind: 'Text', text: 'x' } });
			}
			expect(store.tables[0].tableInfo.name).toBe('a');
		});
	});

	describe('setLimit clamping', () => {
		it('starts at DEFAULT_LIMIT', () => {
			expect(store.limit).toBe(DEFAULT_LIMIT);
		});

		it('passes through values in the valid range', () => {
			store.setLimit(50);
			expect(store.limit).toBe(50);
			store.setLimit(5000);
			expect(store.limit).toBe(5000);
			store.setLimit(1);
			expect(store.limit).toBe(1);
			store.setLimit(MAX_LIMIT);
			expect(store.limit).toBe(MAX_LIMIT);
		});

		it('clamps values above MAX_LIMIT to MAX_LIMIT', () => {
			store.setLimit(MAX_LIMIT + 1);
			expect(store.limit).toBe(MAX_LIMIT);
		});

		it('coerces zero / negative to 1', () => {
			store.setLimit(0);
			expect(store.limit).toBe(1);
			store.setLimit(-10);
			expect(store.limit).toBe(1);
		});

		it('coerces NaN to 1 (regression: Math.max/min(1, NaN) is NaN)', () => {
			store.setLimit(NaN);
			expect(store.limit).toBe(1);
		});
	});

	describe('moveTable', () => {
		it('updates the table x/y', () => {
			store.addTable(makeTable('a'));
			const alias = store.tables[0].alias;
			store.moveTable(alias, 200, 150);
			expect(store.tables[0].x).toBe(200);
			expect(store.tables[0].y).toBe(150);
		});

		it('is a no-op for an unknown alias', () => {
			store.addTable(makeTable('a'));
			const before = [...store.tables];
			store.moveTable('missing', 999, 999);
			expect(store.tables).toEqual(before);
		});
	});

	describe('resizeTable', () => {
		it('updates the table width/height', () => {
			store.addTable(makeTable('a'));
			const alias = store.tables[0].alias;
			store.resizeTable(alias, 320, 360);
			expect(store.tables[0].width).toBe(320);
			expect(store.tables[0].height).toBe(360);
		});

		it('clamps table dimensions to the supported range', () => {
			store.addTable(makeTable('a'));
			const alias = store.tables[0].alias;

			store.resizeTable(alias, 100, 100);
			expect(store.tables[0].width).toBe(180);
			expect(store.tables[0].height).toBe(140);

			store.resizeTable(alias, 900, 900);
			expect(store.tables[0].width).toBe(520);
			expect(store.tables[0].height).toBe(640);
		});

		it('is a no-op for an unknown alias', () => {
			store.addTable(makeTable('a'));
			const before = [...store.tables];
			store.resizeTable('missing', 320, 360);
			expect(store.tables).toEqual(before);
		});
	});

	describe('setGroupConnector at nested paths', () => {
		it('changes the connector on the targeted nested group only', () => {
			store.addTable(makeTable('a'));
			store.addGroupToGroup([], 'And');
			store.addGroupToGroup([0], 'And');

			// Set the outer group to Or, leave the inner group as And.
			store.setGroupConnector([0], 'Or');

			const outer = store.filters.children[0];
			if ('Group' in outer) {
				expect(outer.Group.connector).toBe('Or');
				const inner = outer.Group.children[0];
				if ('Group' in inner) {
					expect(inner.Group.connector).toBe('And');
				}
			}
		});
	});
});
