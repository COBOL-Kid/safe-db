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
			schema_version: 2
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
			schema_version: 2
		};

		hydrateQueryFromSpec(spec, [products], store);

		expect(store.tables).toHaveLength(1);
		expect(store.tables[0].tableInfo.name).toBe('products');
		expect(store.selectedColumns.has('t0.name')).toBe(true);
		expect(store.joins).toHaveLength(0);
		expect(store.filters.children).toHaveLength(0);
	});
});
