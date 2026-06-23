<script lang="ts">
	import { schema } from '$lib/stores/schema.svelte';
	import { qualifiedName, type TableInfo } from '$lib/ir';

	let expanded = $state<Set<string>>(new Set());
	let selected = $state<string | null>(null);

	function tableKey(t: TableInfo): string {
		return qualifiedName(t);
	}

	function toggle(t: TableInfo) {
		const key = tableKey(t);
		const next = new Set(expanded);
		if (next.has(key)) {
			next.delete(key);
		} else {
			next.add(key);
		}
		expanded = next;
	}

	function selectTable(t: TableInfo) {
		selected = tableKey(t);
	}
</script>

<div class="flex h-full flex-col">
	<div class="border-b border-slate-200 p-3">
		<div class="relative">
			<svg class="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8" /><path d="m21 21-4.35-4.35" /></svg>
			<input
				type="text"
				placeholder="Search tables…"
				bind:value={schema.search}
				class="w-full rounded-lg border border-slate-200 bg-white py-2 pl-9 pr-3 text-sm text-slate-900 outline-none transition-colors focus:border-slate-400"
			/>
		</div>
	</div>

	{#if schema.loading}
		<div class="flex flex-1 items-center justify-center p-8">
			<div class="text-center">
				<div class="mx-auto h-6 w-6 animate-spin rounded-full border-2 border-slate-200 border-t-slate-700"></div>
				<p class="mt-3 text-sm text-slate-400">Loading schema…</p>
			</div>
		</div>
	{:else if schema.error}
		<div class="flex flex-1 items-center justify-center p-8">
			<div class="text-center">
				<p class="text-sm font-medium text-red-600">Failed to load schema</p>
				<p class="mt-1 break-all text-sm text-slate-400">{schema.error}</p>
			</div>
		</div>
	{:else if schema.filteredTables.length === 0}
		<div class="flex flex-1 items-center justify-center p-8">
			<p class="text-sm text-slate-400">{schema.search ? 'No tables match your search.' : 'No tables found.'}</p>
		</div>
	{:else}
		<div class="flex-1 overflow-y-auto p-2">
			{#each schema.filteredTables as table (tableKey(table))}
				{@const key = tableKey(table)}
				{@const isOpen = expanded.has(key)}
				<div class="mb-1">
					<div class="flex items-center">
						<button
							type="button"
							onclick={() => toggle(table)}
							class="flex flex-1 items-center gap-2 rounded-lg px-2.5 py-2 text-left text-sm font-medium text-slate-700 transition-colors hover:bg-slate-100"
						>
							<svg class="h-3.5 w-3.5 shrink-0 text-slate-400 transition-transform {isOpen ? 'rotate-90' : ''}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="m9 18 6-6-6-6" /></svg>
							<span class="truncate">{table.name}</span>
							<span class="shrink-0 text-xs text-slate-400">{table.columns.length}</span>
						</button>
					</div>

					{#if isOpen}
						<div class="ml-7 mt-1 space-y-0.5">
							{#each table.columns as col (col.name)}
								<div class="flex items-center gap-2 rounded px-2 py-1.5 text-xs">
									<span class="font-medium text-slate-700">{col.name}</span>
									<span class="text-slate-400">{col.data_type}</span>
									{#if col.is_indexed}
										<span class="ml-auto flex items-center gap-0.5 rounded bg-sky-50 px-1.5 py-0.5 text-[10px] font-medium text-sky-600">
											<svg class="h-2.5 w-2.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2 3 14h9l-1 8 10-12h-9z" /></svg>
											indexed
										</span>
									{/if}
									{#if col.nullable}
										<span class="text-[10px] text-slate-300">null</span>
									{/if}
								</div>
							{/each}
							{#if table.indexes.length > 0}
								<div class="mt-2 border-t border-slate-100 pt-2">
									<p class="px-2 pb-1 text-[10px] font-semibold uppercase tracking-wide text-slate-400">Indexes</p>
									{#each table.indexes as idx (idx.name)}
										<div class="flex items-center gap-1.5 px-2 py-1 text-[11px] text-slate-500">
											{#if idx.is_primary}
												<span class="rounded bg-amber-50 px-1.5 py-0.5 font-medium text-amber-600">PK</span>
											{:else if idx.is_unique}
												<span class="rounded bg-violet-50 px-1.5 py-0.5 font-medium text-violet-600">UQ</span>
											{:else}
												<span class="rounded bg-slate-100 px-1.5 py-0.5 font-medium text-slate-500">IDX</span>
											{/if}
											<span class="truncate">{idx.name}</span>
											<span class="text-slate-300">({idx.columns.join(', ')})</span>
										</div>
									{/each}
								</div>
							{/if}
						</div>
					{/if}
				</div>
			{/each}
		</div>
	{/if}
</div>
