<script lang="ts">
	import { connections } from '$lib/stores/connections.svelte';
	import { schema } from '$lib/stores/schema.svelte';
	import { query } from '$lib/stores/query.svelte';
	import SchemaBrowser from '$lib/components/SchemaBrowser.svelte';
	import Canvas from '$lib/components/Canvas.svelte';
	import ResultsTable from '$lib/components/ResultsTable.svelte';
	import { browser } from '$app/environment';
	import { FILTER_OPS, MAX_LIMIT, type FilterOp, type TableInfo } from '$lib/ir';

	$effect(() => {
		if (browser && connections.activeId && !schema.schema && !schema.loading) {
			schema.load(connections.activeId);
		}
	});

	const dialectLabels: Record<string, string> = {
		Postgres: 'PostgreSQL',
		MySql: 'MySQL'
	};

	function addTable(table: TableInfo) {
		query.addTable(table);
	}

	function handleRun() {
		if (connections.activeId) {
			query.run(connections.activeId);
		}
	}

	function handleClear() {
		query.clear();
	}

	let showAddFilter = $state(false);
	let filterAlias = $state('');
	let filterColumn = $state('');
	let filterOp = $state<FilterOp>('Eq');
	let filterValue = $state('');

	function startAddFilter() {
		showAddFilter = true;
		filterAlias = query.tables[0]?.alias ?? '';
		filterColumn = '';
		filterOp = 'Eq';
		filterValue = '';
	}

	function confirmAddFilter() {
		if (!filterAlias || !filterColumn) return;
		const op = filterOp;
		const needsValue = op !== 'IsNull' && op !== 'IsNotNull';
		query.addFilter({
			table_alias: filterAlias,
			column: filterColumn,
			op,
			value: needsValue ? filterValue || null : null
		});
		showAddFilter = false;
	}

	function getColumnName(alias: string, column: string): string {
		const t = query.tables.find((t) => t.alias === alias);
		return t ? `${t.tableInfo.name}.${column}` : column;
	}

	function getTableColumns(alias: string) {
		const t = query.tables.find((t) => t.alias === alias);
		return t?.tableInfo.columns ?? [];
	}

	let resultsHeight = $state(240);
	let resizing = $state(false);

	function startResize(e: MouseEvent) {
		resizing = true;
		e.preventDefault();
	}

	function handleResize(e: MouseEvent) {
		if (!resizing) return;
		const panel = document.getElementById('builder-main');
		if (!panel) return;
		const rect = panel.getBoundingClientRect();
		const newHeight = rect.bottom - e.clientY;
		resultsHeight = Math.max(100, Math.min(rect.height - 200, newHeight));
	}

	function stopResize() {
		resizing = false;
	}
</script>

<svelte:window onmousemove={handleResize} onmouseup={stopResize} />

<div class="flex flex-1 flex-col overflow-hidden">
	<div class="flex items-center justify-between border-b border-slate-200 px-6 py-3">
		<div class="flex items-center gap-3">
			{#if connections.active}
				<div class="flex h-8 w-8 items-center justify-center rounded-lg bg-slate-100 text-xs font-bold text-slate-600">
					{dialectLabels[connections.active.dialect]?.slice(0, 2) ?? 'DB'}
				</div>
				<div>
					<h1 class="text-base font-semibold tracking-tight text-slate-900">{connections.active.name}</h1>
					<p class="text-xs text-slate-400">{dialectLabels[connections.active.dialect]} · {connections.active.database}</p>
				</div>
			{:else}
				<h1 class="text-xl font-semibold tracking-tight text-slate-900">Query Builder</h1>
			{/if}
		</div>

		<div class="flex items-center gap-3">
			{#if query.tables.length > 0}
				<div class="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5">
					<label class="text-xs font-medium text-slate-500" for="limit-input">Limit</label>
					<input
						id="limit-input"
						type="number"
						min="1"
						max={MAX_LIMIT}
						value={query.limit}
						oninput={(e) => query.setLimit(parseInt(e.currentTarget.value) || 1)}
						class="w-16 rounded border border-slate-200 px-2 py-0.5 text-sm text-slate-700 outline-none focus:border-slate-400"
					/>
				</div>
				<button
					type="button"
					onclick={handleClear}
					class="rounded-lg px-3 py-2 text-sm font-medium text-slate-500 transition-colors hover:text-slate-700"
				>
					Clear
				</button>
			{/if}
			<button
				type="button"
				onclick={handleRun}
				disabled={!query.canRun}
				class="flex items-center gap-2 rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-slate-700 disabled:opacity-40"
			>
				{#if query.running}
					<div class="h-3.5 w-3.5 animate-spin rounded-full border-2 border-white/30 border-t-white"></div>
					Running…
				{:else}
					<svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 3l14 9-14 9V3z" /></svg>
					Run Query
				{/if}
			</button>
		</div>
	</div>

	{#if !connections.activeId}
		<div class="flex flex-1 items-center justify-center p-8">
			<div class="text-center">
				<div class="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-slate-100 text-slate-400">
					<svg class="h-7 w-7" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M4 6h16M4 12h16M4 18h10" /></svg>
				</div>
				<p class="mt-4 text-sm font-medium text-slate-600">No connection selected</p>
				<p class="mt-1 text-sm text-slate-400">Connect to a database to start building queries.</p>
				<a href="/connections" class="mt-5 inline-block rounded-lg border border-slate-200 bg-white px-4 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-50">
					Go to Connections
				</a>
			</div>
		</div>
	{:else}
		<div id="builder-main" class="flex flex-1 flex-col overflow-hidden">
			<div class="flex flex-1 overflow-hidden">
				<aside class="w-72 shrink-0 border-r border-slate-200 bg-white">
					<SchemaBrowser onAddTable={addTable} />
				</aside>

				<div class="flex flex-1 flex-col overflow-hidden">
					{#if query.joins.length > 0 || query.filters.length > 0 || showAddFilter}
						<div class="flex flex-wrap items-center gap-2 border-b border-slate-200 bg-white px-4 py-2">
							{#each query.joins as join, i (i)}
								<span class="flex items-center gap-1.5 rounded-full bg-sky-50 px-3 py-1 text-xs font-medium text-sky-700">
									join: {getColumnName(join.left_alias, join.left_column)} = {getColumnName(join.right_alias, join.right_column)}
									<button type="button" onclick={() => query.removeJoin(i)} class="text-sky-400 hover:text-sky-600" aria-label="Remove join">
										<svg class="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
									</button>
								</span>
							{/each}
							{#each query.filters as filter, i (i)}
								<span class="flex items-center gap-1.5 rounded-full bg-violet-50 px-3 py-1 text-xs font-medium text-violet-700">
									{getColumnName(filter.table_alias, filter.column)}
									{FILTER_OPS.find((f) => f.value === filter.op)?.label}
									{#if filter.value !== null}{filter.value}{/if}
									<button type="button" onclick={() => query.removeFilter(i)} class="text-violet-400 hover:text-violet-600" aria-label="Remove filter">
										<svg class="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
									</button>
								</span>
							{/each}
							{#if showAddFilter}
								<div class="flex items-center gap-1.5 rounded-full bg-slate-50 px-2 py-1">
									<select bind:value={filterAlias} class="rounded border border-slate-200 px-1.5 py-0.5 text-xs text-slate-600 outline-none">
										{#each query.tables as t (t.alias)}
											<option value={t.alias}>{t.tableInfo.name}</option>
										{/each}
									</select>
									<select bind:value={filterColumn} class="rounded border border-slate-200 px-1.5 py-0.5 text-xs text-slate-600 outline-none">
										<option value="">col</option>
										{#each getTableColumns(filterAlias) as col (col.name)}
											<option value={col.name}>{col.name}</option>
										{/each}
									</select>
									<select bind:value={filterOp} class="rounded border border-slate-200 px-1.5 py-0.5 text-xs text-slate-600 outline-none">
										{#each FILTER_OPS as op (op.value)}
											<option value={op.value}>{op.label}</option>
										{/each}
									</select>
									{#if filterOp !== 'IsNull' && filterOp !== 'IsNotNull'}
										<input bind:value={filterValue} placeholder="value" class="w-20 rounded border border-slate-200 px-1.5 py-0.5 text-xs text-slate-600 outline-none" />
									{/if}
									<button type="button" onclick={confirmAddFilter} class="rounded bg-slate-900 px-2 py-0.5 text-xs text-white hover:bg-slate-700">Add</button>
									<button type="button" onclick={() => (showAddFilter = false)} class="text-xs text-slate-400 hover:text-slate-600">Cancel</button>
								</div>
							{:else}
								<button type="button" onclick={startAddFilter} class="flex items-center gap-1 rounded-full border border-dashed border-slate-300 px-3 py-1 text-xs text-slate-400 transition-colors hover:border-slate-400 hover:text-slate-600">
									<svg class="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14" /></svg>
									Add filter
								</button>
							{/if}
						</div>
					{/if}

					<div class="relative flex-1 overflow-hidden bg-slate-50">
						{#if query.tables.length === 0}
							<div class="flex h-full items-center justify-center">
								<div class="text-center">
									<div class="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-white text-slate-300 shadow-sm">
										<svg class="h-7 w-7" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2" /><path d="M3 9h18M3 15h18M9 3v18M15 3v18" /></svg>
									</div>
									<p class="mt-4 text-sm font-medium text-slate-500">Empty canvas</p>
									<p class="mt-1 text-sm text-slate-400">Click + next to a table in the sidebar to add it.</p>
								</div>
							</div>
						{:else}
							<Canvas />
						{/if}
					</div>

					{#if query.error}
						<div class="border-t border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
							{query.error}
						</div>
					{/if}

					{#if query.results}
						<div class="border-t border-slate-200 bg-white" style="height: {resultsHeight}px;">
							<button type="button" class="h-1.5 w-full cursor-row-resize bg-slate-200 hover:bg-slate-300" aria-label="Resize results panel" onmousedown={startResize}></button>
							<ResultsTable result={query.results} />
						</div>
					{/if}
				</div>
			</div>
		</div>
	{/if}
</div>
