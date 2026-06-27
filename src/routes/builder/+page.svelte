<script lang="ts">
	import { connections } from '$lib/stores/connections.svelte';
	import { schema } from '$lib/stores/schema.svelte';
	import { query } from '$lib/stores/query.svelte';
	import { savedQueries } from '$lib/stores/saved-queries.svelte';
	import SchemaBrowser from '$lib/components/SchemaBrowser.svelte';
	import Canvas from '$lib/components/Canvas.svelte';
	import ResultsTable from '$lib/components/ResultsTable.svelte';
	import FilterBuilder from '$lib/components/FilterBuilder.svelte';
	import { browser } from '$app/environment';
	import { MAX_LIMIT, type TableInfo } from '$lib/ir';

	$effect(() => {
		if (browser && connections.activeId && !schema.schema && !schema.loading) {
			schema.load(connections.activeId);
		}
	});

	const dialectLabels: Record<string, string> = {
		Postgres: 'PostgreSQL',
		MySql: 'MySQL',
		Mssql: 'SQL Server',
		Oracle: 'Oracle'
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

	function tableNameForAlias(alias: string): string {
		return query.tables.find((t) => t.alias === alias)?.tableInfo.name ?? alias;
	}

	async function handleSaveQuery() {
		if (!connections.activeId) return;
		const name = prompt('Name this query:', `Query on ${query.tables.map((t) => t.tableInfo.name).join(', ')}`);
		if (!name) return;
		await savedQueries.save({
			id: crypto.randomUUID(),
			name,
			connection_id: connections.activeId,
			spec: query.spec,
			created_at: Date.now().toString()
		});
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
	<div class="flex items-center justify-between border-b border-slate-200 px-6 py-3 dark:border-slate-800">
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
				<div class="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5 dark:border-slate-700 dark:bg-slate-900">
					<label class="text-xs font-medium text-slate-500 dark:text-slate-400" for="limit-input">Limit</label>
					<input
						id="limit-input"
						type="number"
						min="1"
						max={MAX_LIMIT}
						value={query.limit}
						oninput={(e) => query.setLimit(parseInt(e.currentTarget.value) || 1)}
						class="w-16 rounded border border-slate-200 px-2 py-0.5 text-sm outline-none focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800"
					/>
				</div>
				<button
					type="button"
					onclick={handleSaveQuery}
					class="rounded-lg px-3 py-2 text-sm font-medium text-slate-500 transition-colors hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200"
				>
					Save
				</button>
				<button
					type="button"
					onclick={handleClear}
					class="rounded-lg px-3 py-2 text-sm font-medium text-slate-500 transition-colors hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200"
				>
					Clear
				</button>
			{/if}
			<button
				type="button"
				onclick={handleRun}
				disabled={!query.canRun}
				class="flex items-center gap-2 rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-slate-700 disabled:opacity-40 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-300"
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
				{#if query.joins.length > 0}
					<div class="flex flex-wrap items-center gap-2 border-b border-slate-200 bg-white px-4 py-2">
						{#each query.joins as join, i (i)}
							<span class="flex items-center gap-1.5 rounded-full bg-sky-50 px-3 py-1 text-xs font-medium text-sky-700">
								join: {tableNameForAlias(join.left_alias)}.{join.left_column} = {tableNameForAlias(join.right_alias)}.{join.right_column}
								<button type="button" onclick={() => query.removeJoin(i)} class="text-sky-400 hover:text-sky-600" aria-label="Remove join">
									<svg class="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
								</button>
							</span>
						{/each}
					</div>
				{/if}

				{#if query.tables.length > 0}
					<div class="border-b border-slate-200 bg-white px-4 py-2">
						<FilterBuilder />
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
