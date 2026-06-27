<script lang="ts">
	import type { CanvasTable } from '$lib/stores/query.svelte';
	import type { FilterGroup, GroupConnector, FilterSpec, FilterValue, FilterLiteral } from '$lib/ir';
	import { query } from '$lib/stores/query.svelte';
	import { opsForColumn, literalKindForColumn, valueKindForOp } from '$lib/ir';
	import FilterRow from './FilterRow.svelte';
	import FilterGroupCard from './FilterGroupCard.svelte';

	let {
		group,
		path,
		tables,
		depth = 0
	}: {
		group: FilterGroup;
		path: number[];
		tables: CanvasTable[];
		depth?: number;
	} = $props();

	function toggleConnector() {
		const newConnector: GroupConnector = group.connector === 'And' ? 'Or' : 'And';
		query.setGroupConnector(path, newConnector);
	}

	function remove() {
		if (path.length > 0) {
			query.removeFilterNode(path);
		}
	}

	function addFilter() {
		const t = tables[0];
		if (!t || t.tableInfo.columns.length === 0) return;
		const col = t.tableInfo.columns[0];
		const ops = opsForColumn(col.data_type);
		const op = ops[0];
		const kind = literalKindForColumn(col.data_type);
		const vk = valueKindForOp(op);
		let value: FilterValue | null = null;
		if (vk === 'Single') {
			value = { Single: { kind, text: '' } };
		} else if (vk === 'List') {
			value = { List: [{ kind, text: '' }] };
		} else if (vk === 'Pair') {
			const pair: [FilterLiteral, FilterLiteral] = [
				{ kind, text: '' },
				{ kind, text: '' }
			];
			value = { Pair: pair };
		}
		const spec: FilterSpec = { table_alias: t.alias, column: col.name, op, value };
		query.addFilterToGroup(path, spec);
	}

	function addGroup() {
		query.addGroupToGroup(path, 'And');
	}

	let bgClass = $derived(
		depth === 0
			? 'bg-transparent'
			: depth % 2 === 1
				? 'bg-slate-50'
				: 'bg-slate-100/60'
	);

	let borderClass = $derived(depth === 0 ? '' : 'border border-slate-200 rounded-lg');
</script>

<div class="flex flex-col gap-1.5 {bgClass} {borderClass} p-1.5">
	<div class="flex items-center gap-2">
		<button
			type="button"
			onclick={toggleConnector}
			class="rounded-full px-2.5 py-0.5 text-xs font-semibold transition-colors {group.connector === 'And'
				? 'bg-sky-100 text-sky-700 hover:bg-sky-200'
				: 'bg-amber-100 text-amber-700 hover:bg-amber-200'}"
		>
			{group.connector.toUpperCase()}
		</button>
		{#if depth > 0}
			<button
				type="button"
				onclick={remove}
				class="text-slate-300 transition-colors hover:text-red-500"
				aria-label="Remove group"
			>
				<svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
			</button>
		{/if}
	</div>

	<div class="flex flex-col gap-1.5 {depth > 0 ? 'pl-4' : ''}">
		{#each group.children as child, i (i)}
			{@const childPath = [...path, i]}
			{#if 'Leaf' in child}
				<FilterRow filter={child.Leaf} path={childPath} {tables} />
			{:else}
				<FilterGroupCard group={child.Group} path={childPath} {tables} depth={depth + 1} />
			{/if}
		{/each}
	</div>

	{#if group.children.length === 0}
		<div class="py-2 text-center text-xs text-slate-300">No conditions</div>
	{/if}

	<div class="flex items-center gap-2 {depth > 0 ? 'pl-4' : ''}">
		<button
			type="button"
			onclick={addFilter}
			class="flex items-center gap-1 rounded-full border border-dashed border-slate-300 px-2.5 py-0.5 text-xs text-slate-400 transition-colors hover:border-slate-400 hover:text-slate-600"
		>
			<svg class="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14" /></svg>
			Filter
		</button>
		<button
			type="button"
			onclick={addGroup}
			class="flex items-center gap-1 rounded-full border border-dashed border-slate-300 px-2.5 py-0.5 text-xs text-slate-400 transition-colors hover:border-slate-400 hover:text-slate-600"
		>
			<svg class="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2" /><path d="M3 9h18M3 15h18M9 3v18M15 3v18" /></svg>
			Group
		</button>
	</div>
</div>
