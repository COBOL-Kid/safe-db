import type { FilterSpec, JoinSpec, QuerySpec, TableInfo } from '$lib/ir';

export interface QueryHydrationTarget {
	clear(): void;
	addTable(tableInfo: TableInfo): void;
	readonly tables: ReadonlyArray<{ alias: string }>;
	toggleColumn(alias: string, column: string): void;
	addJoin(join: JoinSpec): void;
	addFilter(filter: FilterSpec): void;
	setLimit(limit: number): void;
}

/** Restore a saved or history query spec into the query store, remapping table aliases. */
export function hydrateQueryFromSpec(
	spec: QuerySpec,
	schemaTables: TableInfo[],
	target: QueryHydrationTarget
): void {
	target.clear();

	const aliasMap = new Map<string, string>();
	for (const t of spec.tables) {
		const tableInfo = schemaTables.find(
			(st) => st.schema === t.schema && st.name === t.name
		);
		if (tableInfo) {
			target.addTable(tableInfo);
			const newAlias = target.tables[target.tables.length - 1]?.alias;
			if (newAlias) {
				aliasMap.set(t.alias, newAlias);
			}
		}
	}

	for (const col of spec.columns) {
		const newAlias = aliasMap.get(col.table_alias);
		if (newAlias) {
			target.toggleColumn(newAlias, col.column);
		}
	}

	for (const join of spec.joins) {
		target.addJoin({
			left_alias: aliasMap.get(join.left_alias) ?? join.left_alias,
			left_column: join.left_column,
			right_alias: aliasMap.get(join.right_alias) ?? join.right_alias,
			right_column: join.right_column
		});
	}

	for (const filter of spec.filters) {
		target.addFilter({
			...filter,
			table_alias: aliasMap.get(filter.table_alias) ?? filter.table_alias
		});
	}

	target.setLimit(spec.limit);
}
