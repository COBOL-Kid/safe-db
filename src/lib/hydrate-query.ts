import type {
	FilterGroup,
	FilterNode,
	FilterSpec,
	GroupConnector,
	JoinSpec,
	QuerySpec,
	TableInfo
} from '$lib/ir';

export interface QueryHydrationTarget {
	clear(): void;
	addTable(tableInfo: TableInfo): void;
	readonly tables: ReadonlyArray<{ alias: string }>;
	toggleColumn(alias: string, column: string): void;
	addJoin(join: JoinSpec): void;
	setFilters(group: FilterGroup): void;
	setConnectorOverrides(map: Record<string, GroupConnector>): void;
	setLimit(limit: number): void;
}

export interface HydrationWarnings {
	droppedTables: string[];
	droppedJoins: number;
	droppedFilters: boolean;
}

function schemaKey(schema: string, name: string): string {
	return `${schema}\0${name}`;
}

function countFilterLeaves(group: FilterGroup): number {
	return group.children.reduce((sum, child) => {
		if ('Leaf' in child) return sum + 1;
		return sum + countFilterLeaves(child.Group);
	}, 0);
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
): HydrationWarnings {
	target.clear();

	const schemaByKey = new Map(
		schemaTables.map((table) => [schemaKey(table.schema, table.name), table])
	);
	const aliasMap = new Map<string, string>();
	const droppedTables: string[] = [];

	for (const t of spec.tables) {
		const tableInfo = schemaByKey.get(schemaKey(t.schema, t.name));
		if (!tableInfo) {
			droppedTables.push(`${t.schema}.${t.name}`);
			continue;
		}

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

	let droppedJoins = 0;
	for (const join of spec.joins) {
		const leftAlias = aliasMap.get(join.left_alias);
		const rightAlias = aliasMap.get(join.right_alias);
		if (!leftAlias || !rightAlias) {
			droppedJoins += 1;
			continue;
		}

		target.addJoin({
			left_alias: leftAlias,
			left_column: join.left_column,
			right_alias: rightAlias,
			right_column: join.right_column
		});
	}

	const originalFilterLeaves = countFilterLeaves(spec.filters);
	const remappedFilters = remapFilterGroup(spec.filters, aliasMap);
	const droppedFilters = countFilterLeaves(remappedFilters) < originalFilterLeaves;
	target.setFilters(remappedFilters);

	target.setConnectorOverrides(spec.connector_overrides ?? {});

	target.setLimit(spec.limit);

	return { droppedTables, droppedJoins, droppedFilters };
}

export function formatHydrationWarning(warnings: HydrationWarnings): string | null {
	const parts: string[] = [];
	if (warnings.droppedTables.length > 0) {
		parts.push(`missing tables: ${warnings.droppedTables.join(', ')}`);
	}
	if (warnings.droppedJoins > 0) {
		parts.push(
			`${warnings.droppedJoins} join${warnings.droppedJoins !== 1 ? 's' : ''} could not be restored`
		);
	}
	if (warnings.droppedFilters) {
		parts.push('some filters were dropped');
	}
	if (parts.length === 0) return null;
	return `Query restored partially (${parts.join('; ')}). Review before running.`;
}
