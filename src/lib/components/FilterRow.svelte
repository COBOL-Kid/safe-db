<script lang="ts">
	import type { CanvasTable } from '$lib/stores/query.svelte';
	import type { FilterSpec, FilterOp, FilterLiteral, FilterValue } from '$lib/ir';
	import { query } from '$lib/stores/query.svelte';
	import {
		opsForColumn,
		valueKindForOp,
		literalKindForColumn,
		opLabel
	} from '$lib/ir';
	import { MAX_IN_LIST_SIZE } from '$lib/limits';

	let {
		filter,
		path,
		tables
	}: {
		filter: FilterSpec;
		path: number[];
		tables: CanvasTable[];
	} = $props();

	let table = $derived(tables.find((t) => t.alias === filter.table_alias));
	let columns = $derived(table?.tableInfo.columns ?? []);
	let columnInfo = $derived(columns.find((c) => c.name === filter.column));
	let availableOps = $derived(columnInfo ? opsForColumn(columnInfo.data_type) : []);
	let valueKind = $derived(valueKindForOp(filter.op));

	function update(newFilter: FilterSpec) {
		query.updateFilter(path, newFilter);
	}

	function changeTable(alias: string) {
		const t = tables.find((t) => t.alias === alias);
		if (!t) return;
		const firstCol = t.tableInfo.columns[0];
		if (!firstCol) return;
		const ops = opsForColumn(firstCol.data_type);
		const op = ops.includes(filter.op) ? filter.op : ops[0];
		update({
			table_alias: alias,
			column: firstCol.name,
			op,
			value: rebuildValue(op, firstCol.data_type, filter.value)
		});
	}

	function changeColumn(colName: string) {
		if (!columnInfo) return;
		const col = columns.find((c) => c.name === colName);
		if (!col) return;
		const ops = opsForColumn(col.data_type);
		const op = ops.includes(filter.op) ? filter.op : ops[0];
		update({
			table_alias: filter.table_alias,
			column: colName,
			op,
			value: rebuildValue(op, col.data_type, filter.value)
		});
	}

	function changeOp(newOp: FilterOp) {
		if (!columnInfo) return;
		update({
			table_alias: filter.table_alias,
			column: filter.column,
			op: newOp,
			value: rebuildValue(newOp, columnInfo.data_type, filter.value)
		});
	}

	function rebuildValue(op: FilterOp, dataType: string, oldValue: FilterValue | null): FilterValue | null {
		const kind = literalKindForColumn(dataType);
		const vk = valueKindForOp(op);
		if (vk === 'None') return null;
		if (vk === 'Single') {
			const text = extractSingleText(oldValue);
			return { Single: { kind, text } };
		}
		if (vk === 'List') {
			const items = extractList(oldValue);
			return { List: items.length > 0 ? items : [{ kind, text: '' }] };
		}
		if (vk === 'Pair') {
			const [from, to] = extractPair(oldValue);
			return { Pair: [{ kind, text: from }, { kind, text: to }] };
		}
		return null;
	}

	function extractSingleText(val: FilterValue | null): string {
		if (val && 'Single' in val) return val.Single.text;
		return '';
	}

	function extractList(val: FilterValue | null): FilterLiteral[] {
		if (val && 'List' in val) return val.List;
		return [];
	}

	function extractPair(val: FilterValue | null): [string, string] {
		if (val && 'Pair' in val) return [val.Pair[0].text, val.Pair[1].text];
		return ['', ''];
	}

	function updateSingleText(text: string) {
		if (!filter.value || !('Single' in filter.value)) return;
		update({
			...filter,
			value: { Single: { ...filter.value.Single, text } }
		});
	}

	function updatePairFrom(text: string) {
		if (!filter.value || !('Pair' in filter.value)) return;
		update({
			...filter,
			value: { Pair: [{ ...filter.value.Pair[0], text }, filter.value.Pair[1]] }
		});
	}

	function updatePairTo(text: string) {
		if (!filter.value || !('Pair' in filter.value)) return;
		update({
			...filter,
			value: { Pair: [filter.value.Pair[0], { ...filter.value.Pair[1], text }] }
		});
	}

	function updateListItem(index: number, text: string) {
		if (!filter.value || !('List' in filter.value)) return;
		const items = filter.value.List.map((lit, i) => (i === index ? { ...lit, text } : lit));
		update({ ...filter, value: { List: items } });
	}

	function addListItem() {
		if (!filter.value || !('List' in filter.value)) return;
		if (filter.value.List.length >= MAX_IN_LIST_SIZE) return;
		const kind = filter.value.List[0]?.kind ?? 'Text';
		update({ ...filter, value: { List: [...filter.value.List, { kind, text: '' }] } });
	}

	function removeListItem(index: number) {
		if (!filter.value || !('List' in filter.value)) return;
		if (filter.value.List.length <= 1) return;
		update({ ...filter, value: { List: filter.value.List.filter((_, i) => i !== index) } });
	}

	let inputType = $derived.by(() => {
		if (!columnInfo) return 'text';
		const cat = literalKindForColumn(columnInfo.data_type);
		switch (cat) {
			case 'Int': return 'number';
			case 'Decimal': return 'number';
			case 'Float': return 'number';
			case 'Date': return 'date';
			case 'DateTime': return 'datetime-local';
			default: return 'text';
		}
	});

	let inputStep = $derived(
		columnInfo && ['Decimal', 'Float'].includes(literalKindForColumn(columnInfo.data_type)) ? 'any' : undefined
	);
</script>

<div class="flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs dark:border-slate-700 dark:bg-slate-900">
	<select
		value={filter.table_alias}
		onchange={(e) => changeTable((e.currentTarget as HTMLSelectElement).value)}
		aria-label="Filter table"
		class="rounded border border-slate-200 bg-white px-1.5 py-0.5 text-xs text-slate-600 outline-none focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 dark:focus:border-slate-500"
	>
		{#each tables as t (t.alias)}
			<option value={t.alias}>{t.tableInfo.name}</option>
		{/each}
	</select>

	<select
		value={filter.column}
		onchange={(e) => changeColumn((e.currentTarget as HTMLSelectElement).value)}
		aria-label="Filter column"
		class="rounded border border-slate-200 bg-white px-1.5 py-0.5 text-xs text-slate-600 outline-none focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 dark:focus:border-slate-500"
	>
		{#each columns as col (col.name)}
			<option value={col.name}>{col.name}</option>
		{/each}
	</select>

	<select
		value={filter.op}
		onchange={(e) => changeOp((e.currentTarget as HTMLSelectElement).value as FilterOp)}
		aria-label="Filter operator"
		class="rounded border border-slate-200 bg-white px-1.5 py-0.5 text-xs text-slate-600 outline-none focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 dark:focus:border-slate-500"
	>
		{#each availableOps as op (op)}
			<option value={op}>{opLabel(op)}</option>
		{/each}
	</select>

	{#if valueKind === 'None'}
		<div class="w-20"></div>
	{:else if valueKind === 'Single' && filter.value && 'Single' in filter.value}
		{#if columnInfo && literalKindForColumn(columnInfo.data_type) === 'Bool'}
			<select
				value={filter.value.Single.text}
				onchange={(e) => updateSingleText((e.currentTarget as HTMLSelectElement).value)}
				class="w-20 rounded border border-slate-200 bg-white px-1.5 py-0.5 text-xs text-slate-600 outline-none focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 dark:focus:border-slate-500"
			>
				<option value="true">true</option>
				<option value="false">false</option>
			</select>
		{:else}
			<input
				type={inputType}
				step={inputStep}
				value={filter.value.Single.text}
				oninput={(e) => updateSingleText((e.currentTarget as HTMLInputElement).value)}
				placeholder="value"
				class="w-24 rounded border border-slate-200 bg-white px-1.5 py-0.5 text-xs text-slate-600 outline-none focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 dark:placeholder:text-slate-500 dark:focus:border-slate-500"
			/>
		{/if}
	{:else if valueKind === 'Pair' && filter.value && 'Pair' in filter.value}
		<input
			type={inputType}
			step={inputStep}
			value={filter.value.Pair[0].text}
			oninput={(e) => updatePairFrom((e.currentTarget as HTMLInputElement).value)}
			placeholder="from"
			class="w-20 rounded border border-slate-200 bg-white px-1.5 py-0.5 text-xs text-slate-600 outline-none focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 dark:placeholder:text-slate-500 dark:focus:border-slate-500"
		/>
		<span class="text-slate-400 dark:text-slate-500">and</span>
		<input
			type={inputType}
			step={inputStep}
			value={filter.value.Pair[1].text}
			oninput={(e) => updatePairTo((e.currentTarget as HTMLInputElement).value)}
			placeholder="to"
			class="w-20 rounded border border-slate-200 bg-white px-1.5 py-0.5 text-xs text-slate-600 outline-none focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 dark:placeholder:text-slate-500 dark:focus:border-slate-500"
		/>
	{:else if valueKind === 'List' && filter.value && 'List' in filter.value}
		<div class="flex flex-wrap items-center gap-1">
			{#each filter.value.List as lit, i (i)}
				<input
					type={inputType}
					step={inputStep}
					value={lit.text}
					oninput={(e) => updateListItem(i, (e.currentTarget as HTMLInputElement).value)}
					placeholder="value"
					class="w-16 rounded border border-slate-200 bg-white px-1.5 py-0.5 text-xs text-slate-600 outline-none focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 dark:placeholder:text-slate-500 dark:focus:border-slate-500"
				/>
				{#if filter.value.List.length > 1}
					<button
						type="button"
						onclick={() => removeListItem(i)}
						class="text-slate-300 hover:text-red-500 dark:text-slate-500 dark:hover:text-red-400"
						aria-label="Remove value"
					>
						<svg class="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
					</button>
				{/if}
			{/each}
			<button
				type="button"
				onclick={addListItem}
				class="flex h-5 w-5 items-center justify-center rounded border border-slate-200 text-slate-400 hover:bg-slate-50 hover:text-slate-600 dark:border-slate-700 dark:text-slate-500 dark:hover:bg-slate-800 dark:hover:text-slate-300"
				aria-label="Add value"
			>
				<svg class="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14" /></svg>
			</button>
		</div>
	{/if}

	<button
		type="button"
		onclick={() => query.removeFilterNode(path)}
		class="text-slate-300 transition-colors hover:text-red-500 dark:text-slate-500 dark:hover:text-red-400"
		aria-label="Remove filter"
	>
		<svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
	</button>
</div>
