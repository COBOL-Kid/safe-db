import { beforeEach, describe, expect, it } from 'vitest';
import { QueryStore } from '$lib/stores/query.svelte';
import type { TableInfo } from '$lib/ir';

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
			value: 'x'
		});

		store.removeTable(aliasA);

		expect(store.tables).toHaveLength(1);
		expect(store.selectedColumns.size).toBe(1);
		expect([...store.selectedColumns][0]).toBe(`${aliasB}.name`);
		expect(store.joins).toHaveLength(0);
		expect(store.filters).toHaveLength(0);
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

	it('clamps limit between 1 and MAX_LIMIT', () => {
		store.setLimit(0);
		expect(store.limit).toBe(1);
		store.setLimit(9999);
		expect(store.limit).toBe(1000);
	});

	it('builds spec from current state', () => {
		store.addTable(makeTable('products'));
		const alias = store.tables[0].alias;
		store.toggleColumn(alias, 'id');
		store.setLimit(50);

		expect(store.spec).toEqual({
			tables: [{ schema: 'public', name: 'products', alias }],
			columns: [{ table_alias: alias, column: 'id' }],
			joins: [],
			filters: [],
			limit: 50
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
});
