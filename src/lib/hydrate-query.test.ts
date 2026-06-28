import { beforeEach, describe, expect, it } from 'vitest';
import { columnKey } from '$lib/column-keys';
import { hydrateQueryFromSpec, formatHydrationWarning } from '$lib/hydrate-query';
import { QueryStore } from '$lib/stores/query.svelte';
import type { QuerySpec, TableInfo } from '$lib/ir';

const products: TableInfo = {
	schema: 'safedb_test',
	name: 'products',
	columns: [
		{ name: 'id', data_type: 'int', nullable: false, is_indexed: true },
		{ name: 'name', data_type: 'varchar', nullable: false, is_indexed: false },
		{ name: 'created_at', data_type: 'timestamp', nullable: false, is_indexed: false }
	],
	indexes: []
};

const categories: TableInfo = {
	schema: 'safedb_test',
	name: 'categories',
	columns: [
		{ name: 'id', data_type: 'int', nullable: false, is_indexed: true },
		{ name: 'name', data_type: 'varchar', nullable: false, is_indexed: true }
	],
	indexes: []
};

describe('hydrateQueryFromSpec', () => {
	let store: QueryStore;

	beforeEach(() => {
		store = new QueryStore();
	});

	it('remaps aliases and restores columns, joins, filters, and limit', () => {
		const spec: QuerySpec = {
			tables: [
				{ schema: 'safedb_test', name: 'products', alias: 'saved_t0' },
				{ schema: 'safedb_test', name: 'categories', alias: 'saved_t1' }
			],
			columns: [
				{ table_alias: 'saved_t0', column: 'name' },
				{ table_alias: 'saved_t1', column: 'name' }
			],
			joins: [
				{
					left_alias: 'saved_t0',
					left_column: 'id',
					right_alias: 'saved_t1',
					right_column: 'id'
				}
			],
			filters: {
				connector: 'And',
				children: [
					{
						Leaf: {
							table_alias: 'saved_t0',
							column: 'name',
							op: 'Like',
							value: { Single: { kind: 'Text', text: '%widget%' } }
						}
					}
				]
			},
			limit: 25,
			schema_version: 2,
			connector_overrides: {}
		};

		hydrateQueryFromSpec(spec, [products, categories], store);

		expect(store.tables).toHaveLength(2);
		expect(store.tables[0].alias).toBe('t0');
		expect(store.tables[1].alias).toBe('t1');
		expect(store.selectedColumns.has(columnKey('t0', 'name'))).toBe(true);
		expect(store.selectedColumns.has(columnKey('t1', 'name'))).toBe(true);
		expect(store.joins[0]).toEqual({
			left_alias: 't0',
			left_column: 'id',
			right_alias: 't1',
			right_column: 'id'
		});
		expect(store.filters.children).toHaveLength(1);
		const leaf = store.filters.children[0];
		expect('Leaf' in leaf).toBe(true);
		if ('Leaf' in leaf) {
			expect(leaf.Leaf.table_alias).toBe('t0');
		}
		expect(store.limit).toBe(25);
	});

	it('skips joins and filters when referenced tables are missing', () => {
		const spec: QuerySpec = {
			tables: [
				{ schema: 'safedb_test', name: 'missing_a', alias: 'saved_t0' },
				{ schema: 'safedb_test', name: 'missing_b', alias: 'saved_t1' },
				{ schema: 'safedb_test', name: 'products', alias: 'saved_t2' }
			],
			columns: [{ table_alias: 'saved_t2', column: 'name' }],
			joins: [
				{
					left_alias: 'saved_t0',
					left_column: 'id',
					right_alias: 'saved_t1',
					right_column: 'id'
				}
			],
			filters: {
				connector: 'And',
				children: [
					{
						Leaf: {
							table_alias: 'saved_t0',
							column: 'name',
							op: 'Like',
							value: { Single: { kind: 'Text', text: '%widget%' } }
						}
					}
				]
			},
			limit: 100,
			schema_version: 2,
			connector_overrides: {}
		};

		const warnings = hydrateQueryFromSpec(spec, [products], store);

		expect(store.tables).toHaveLength(1);
		expect(store.tables[0].tableInfo.name).toBe('products');
		expect(store.selectedColumns.has(columnKey('t0', 'name'))).toBe(true);
		expect(store.joins).toHaveLength(0);
		expect(store.filters.children).toHaveLength(0);
		expect(warnings.droppedTables).toEqual(['safedb_test.missing_a', 'safedb_test.missing_b']);
		expect(warnings.droppedColumns).toEqual([]);
		expect(warnings.droppedJoins).toBe(1);
		expect(warnings.droppedFilters).toBe(true);
		expect(formatHydrationWarning(warnings)).toContain('missing tables');
	});

	it('drops selected columns that no longer exist on restored tables', () => {
		const spec: QuerySpec = {
			tables: [{ schema: 'safedb_test', name: 'products', alias: 'saved_t0' }],
			columns: [
				{ table_alias: 'saved_t0', column: 'name' },
				{ table_alias: 'saved_t0', column: 'deleted_column' }
			],
			joins: [],
			filters: {
				connector: 'And',
				children: []
			},
			limit: 100,
			schema_version: 3,
			connector_overrides: {}
		};

		const warnings = hydrateQueryFromSpec(spec, [products], store);

		expect(store.selectedColumns.has(columnKey('t0', 'name'))).toBe(true);
		expect(store.selectedColumns.has(columnKey('t0', 'deleted_column'))).toBe(false);
		expect(warnings.droppedColumns).toEqual(['saved_t0.deleted_column']);
		expect(formatHydrationWarning(warnings)).toContain('1 selected column could not be restored');
	});

	it('drops joins whose columns no longer exist on restored tables', () => {
		const spec: QuerySpec = {
			tables: [
				{ schema: 'safedb_test', name: 'products', alias: 'saved_t0' },
				{ schema: 'safedb_test', name: 'categories', alias: 'saved_t1' }
			],
			columns: [],
			joins: [
				{
					left_alias: 'saved_t0',
					left_column: 'id',
					right_alias: 'saved_t1',
					right_column: 'missing_id'
				},
				{
					left_alias: 'saved_t0',
					left_column: 'id',
					right_alias: 'saved_t1',
					right_column: 'id'
				}
			],
			filters: {
				connector: 'And',
				children: []
			},
			limit: 100,
			schema_version: 3,
			connector_overrides: {}
		};

		const warnings = hydrateQueryFromSpec(spec, [products, categories], store);

		expect(store.joins).toEqual([
			{
				left_alias: 't0',
				left_column: 'id',
				right_alias: 't1',
				right_column: 'id'
			}
		]);
		expect(warnings.droppedJoins).toBe(1);
		expect(formatHydrationWarning(warnings)).toContain('1 join could not be restored');
	});

	it('remaps aliases inside nested filter groups', () => {
		const spec: QuerySpec = {
			tables: [
				{ schema: 'safedb_test', name: 'products', alias: 'saved_t0' },
				{ schema: 'safedb_test', name: 'categories', alias: 'saved_t1' }
			],
			columns: [{ table_alias: 'saved_t0', column: 'name' }],
			joins: [],
			filters: {
				connector: 'And',
				children: [
					{
						Leaf: {
							table_alias: 'saved_t0',
							column: 'name',
							op: 'Eq',
							value: { Single: { kind: 'Text', text: 'x' } }
						}
					},
					{
						Group: {
							connector: 'Or',
							children: [
								{
									Leaf: {
										table_alias: 'saved_t1',
										column: 'name',
										op: 'Eq',
										value: { Single: { kind: 'Text', text: 'y' } }
									}
								},
								{
									Leaf: {
										table_alias: 'saved_t0',
										column: 'id',
										op: 'Gt',
										value: { Single: { kind: 'Int', text: '5' } }
									}
								}
							]
						}
					}
				]
			},
			limit: 100,
			schema_version: 2,
			connector_overrides: {}
		};

		hydrateQueryFromSpec(spec, [products, categories], store);

		expect(store.filters.children).toHaveLength(2);

		const rootLeaf = store.filters.children[0];
		expect('Leaf' in rootLeaf).toBe(true);
		if ('Leaf' in rootLeaf) {
			expect(rootLeaf.Leaf.table_alias).toBe('t0');
		}

		const nested = store.filters.children[1];
		expect('Group' in nested).toBe(true);
		if ('Group' in nested) {
			expect(nested.Group.connector).toBe('Or');
			expect(nested.Group.children).toHaveLength(2);
			const g0 = nested.Group.children[0];
			if ('Leaf' in g0) {
				expect(g0.Leaf.table_alias).toBe('t1');
			}
			const g1 = nested.Group.children[1];
			if ('Leaf' in g1) {
				expect(g1.Leaf.table_alias).toBe('t0');
			}
		}
	});

	it('drops nested groups whose filters all reference missing tables', () => {
		const spec: QuerySpec = {
			tables: [{ schema: 'safedb_test', name: 'products', alias: 'saved_t0' }],
			columns: [{ table_alias: 'saved_t0', column: 'name' }],
			joins: [],
			filters: {
				connector: 'And',
				children: [
					{
						Leaf: {
							table_alias: 'saved_t0',
							column: 'name',
							op: 'Eq',
							value: { Single: { kind: 'Text', text: 'x' } }
						}
					},
					{
						Group: {
							connector: 'Or',
							children: [
								{
									Leaf: {
										table_alias: 'saved_missing',
										column: 'name',
										op: 'Eq',
										value: { Single: { kind: 'Text', text: 'y' } }
									}
								}
							]
						}
					}
				]
			},
			limit: 100,
			schema_version: 2,
			connector_overrides: {}
		};

		hydrateQueryFromSpec(spec, [products], store);

		// The nested group only referenced a missing table, so it is dropped;
		// only the remapped root leaf survives.
		expect(store.filters.children).toHaveLength(1);
		const leaf = store.filters.children[0];
		expect('Leaf' in leaf).toBe(true);
		if ('Leaf' in leaf) {
			expect(leaf.Leaf.table_alias).toBe('t0');
		}
	});

	it('restores connector_overrides whose child IDs still resolve after remap', () => {
		const spec: QuerySpec = {
			tables: [{ schema: 'safedb_test', name: 'products', alias: 'saved_t0' }],
			columns: [{ table_alias: 'saved_t0', column: 'name' }],
			joins: [],
			filters: {
				connector: 'And',
				children: [
					{
						Leaf: {
							id: 'leaf-id',
							table_alias: 'saved_t0',
							column: 'id',
							op: 'Eq',
							value: { Single: { kind: 'Int', text: '1' } }
						}
					},
					{
						Leaf: {
							id: 'leaf-name',
							table_alias: 'saved_t0',
							column: 'name',
							op: 'Eq',
							value: { Single: { kind: 'Text', text: 'x' } }
						}
					}
				]
			},
			limit: 100,
			schema_version: 2,
			connector_overrides: { 'leaf-name': 'Or' }
		};

		hydrateQueryFromSpec(spec, [products], store);

		expect(store.connectorOverrides).toEqual({ 'leaf-name': 'Or' });
		expect(store.getConnectorForChild([1])).toBe('Or');
	});

	it('normalizes legacy text literal kinds using the restored column type', () => {
		const spec: QuerySpec = {
			tables: [{ schema: 'safedb_test', name: 'products', alias: 'saved_t0' }],
			columns: [{ table_alias: 'saved_t0', column: 'id' }],
			joins: [],
			filters: {
				connector: 'And',
				children: [
					{
						Leaf: {
							id: 'leaf-id',
							table_alias: 'saved_t0',
							column: 'id',
							op: 'In',
							value: {
								List: [
									{ kind: 'Text', text: '1' },
									{ kind: 'Text', text: '2' }
								]
							}
						}
					},
					{
						Leaf: {
							id: 'leaf-created',
							table_alias: 'saved_t0',
							column: 'created_at',
							op: 'Between',
							value: {
								Pair: [
									{ kind: 'Text', text: '2025-01-01T00:00' },
									{ kind: 'Text', text: '2025-01-02T00:00' }
								]
							}
						}
					}
				]
			},
			limit: 100,
			schema_version: 2,
			connector_overrides: {}
		};

		hydrateQueryFromSpec(spec, [products], store);

		const idLeaf = store.filters.children[0];
		expect('Leaf' in idLeaf).toBe(true);
		if ('Leaf' in idLeaf && idLeaf.Leaf.value && 'List' in idLeaf.Leaf.value) {
			expect(idLeaf.Leaf.value.List.map((literal) => literal.kind)).toEqual(['Int', 'Int']);
		}

		const createdLeaf = store.filters.children[1];
		expect('Leaf' in createdLeaf).toBe(true);
		if ('Leaf' in createdLeaf && createdLeaf.Leaf.value && 'Pair' in createdLeaf.Leaf.value) {
			expect(createdLeaf.Leaf.value.Pair.map((literal) => literal.kind)).toEqual([
				'DateTime',
				'DateTime'
			]);
		}
	});

	it('prunes connector_overrides whose IDs no longer resolve after remap drops a group', () => {
		// The nested group only contains a leaf on a missing table, so
		// remapFilterGroup will drop it entirely. An override keyed on the
		// dropped leaf's id must be pruned during restore.
		const spec: QuerySpec = {
			tables: [{ schema: 'safedb_test', name: 'products', alias: 'saved_t0' }],
			columns: [{ table_alias: 'saved_t0', column: 'name' }],
			joins: [],
			filters: {
				connector: 'And',
				children: [
					{
						Leaf: {
							id: 'leaf-id',
							table_alias: 'saved_t0',
							column: 'id',
							op: 'Eq',
							value: { Single: { kind: 'Int', text: '1' } }
						}
					},
					{
						Group: {
							connector: 'Or',
							children: [
								{
									Leaf: {
										id: 'leaf-missing',
										table_alias: 'saved_missing',
										column: 'name',
										op: 'Eq',
										value: { Single: { kind: 'Text', text: 'y' } }
									}
								}
							]
						}
					}
				]
			},
			limit: 100,
			schema_version: 2,
			connector_overrides: { 'leaf-missing': 'Or' }
		};

		hydrateQueryFromSpec(spec, [products], store);

		expect(store.connectorOverrides).toEqual({});
	});
});
