import type { FilterGroup, FilterNode, FilterSpec, JoinSpec, QuerySpec, TableInfo } from '$lib/ir';

export interface QueryHydrationTarget {
	clear(): void;
	addTable(tableInfo: TableInfo): void;
	readonly tables: ReadonlyArray<{ alias: string }>;
	toggleColumn(alias: string, column: string): void;
	addJoin(join: JoinSpec): void;
	setFilters(group: FilterGroup): void;
	setLimit(limit: number): void;
}

function schemaKey(schema: string, name: string): string {
	return `${schema}\0${name}`;
}

function remapFilterGroup(group: FilterGroup, aliasMap: Map<string, string>): FilterGroup {
	const children: FilterNode[] = [];
	for (const child of group.children) {
		if ('Leaf' in child) {
			const tableAlias = aliasMap.get(child.Leaf.table_alias);
			if (tableAlias) {
				children.push({ Leaf: { ...child.Leaf, table_alias: tableAlias } });
			}
		} else {
			const remapped = remapFilterGroup(child.Group, aliasMap);
			if (remapped.children.length > 0) {
				children.push({ Group: remapped });
			}
		}
	}
	return { ...group, children };
}

/** Restore a saved or history query spec into the query store, remapping table aliases. */
export function hydrateQueryFromSpec(
	spec: QuerySpec,
	schemaTables: TableInfo[],
	target: QueryHydrationTarget
): void {
	target.clear();

	const schemaByKey = new Map(
		schemaTables.map((table) => [schemaKey(table.schema, table.name), table])
	);
	const aliasMap = new Map<string, string>();

	for (const t of spec.tables) {
		const tableInfo = schemaByKey.get(schemaKey(t.schema, t.name));
		if (!tableInfo) continue;

		target.addTable(tableInfo);
		const newAlias = target.tables[target.tables.length - 1]?.alias;
		if (newAlias) {
			aliasMap.set(t.alias, newAlias);
		}
	}

	for (const col of spec.columns) {
		const newAlias = aliasMap.get(col.table_alias);
		if (newAlias) {
			target.toggleColumn(newAlias, col.column);
		}
	}

	for (const join of spec.joins) {
		const leftAlias = aliasMap.get(join.left_alias);
		const rightAlias = aliasMap.get(join.right_alias);
		if (!leftAlias || !rightAlias) continue;

		target.addJoin({
			left_alias: leftAlias,
			left_column: join.left_column,
			right_alias: rightAlias,
			right_column: join.right_column
		});
	}

	const remappedFilters = remapFilterGroup(spec.filters, aliasMap);
	target.setFilters(remappedFilters);

	target.setLimit(spec.limit);
}
