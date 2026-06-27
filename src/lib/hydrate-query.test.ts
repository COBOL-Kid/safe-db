import { beforeEach, describe, expect, it } from 'vitest';
import { hydrateQueryFromSpec } from '$lib/hydrate-query';
import { QueryStore } from '$lib/stores/query.svelte';
import type { QuerySpec, TableInfo } from '$lib/ir';

const products: TableInfo = {
	schema: 'safedb_test',
	name: 'products',
	columns: [
		{ name: 'id', data_type: 'int', nullable: false, is_indexed: true },
		{ name: 'name', data_type: 'varchar', nullable: false, is_indexed: false }
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
		expect(store.selectedColumns.has('t0.name')).toBe(true);
		expect(store.selectedColumns.has('t1.name')).toBe(true);
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

		hydrateQueryFromSpec(spec, [products], store);

		expect(store.tables).toHaveLength(1);
		expect(store.tables[0].tableInfo.name).toBe('products');
		expect(store.selectedColumns.has('t0.name')).toBe(true);
		expect(store.joins).toHaveLength(0);
		expect(store.filters.children).toHaveLength(0);
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
