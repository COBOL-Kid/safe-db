<script lang="ts">
	import { history } from '$lib/stores/history.svelte';
	import { connections } from '$lib/stores/connections.svelte';
	import { query } from '$lib/stores/query.svelte';
	import { schema } from '$lib/stores/schema.svelte';
	import { savedQueries } from '$lib/stores/saved-queries.svelte';
	import { browser } from '$app/environment';
	import { goto } from '$app/navigation';
	import { hydrateQueryFromSpec } from '$lib/hydrate-query';
	import type { HistoryEntry } from '$lib/ir';

	$effect(() => {
		if (browser) history.load();
	});

	function formatTime(ts: string): string {
		const sec = parseInt(ts);
		if (isNaN(sec)) return '';
		const d = new Date(sec * 1000);
		const now = new Date();
		const diff = now.getTime() - d.getTime();
		const mins = Math.floor(diff / 60000);
		const hours = Math.floor(diff / 3600000);
		const days = Math.floor(diff / 86400000);
		if (mins < 1) return 'just now';
		if (mins < 60) return `${mins}m ago`;
		if (hours < 24) return `${hours}h ago`;
		if (days < 7) return `${days}d ago`;
		return d.toLocaleDateString();
	}

	function summarizeSpec(entry: HistoryEntry): string {
		const tables = entry.spec.tables.map((t) => t.name).join(', ');
		const cols = entry.spec.columns.length;
		const joins = entry.spec.joins.length;
		const parts = [tables];
		if (cols > 0) parts.push(`${cols} col${cols !== 1 ? 's' : ''}`);
		if (joins > 0) parts.push(`${joins} join${joins !== 1 ? 's' : ''}`);
		parts.push(`limit ${entry.spec.limit}`);
		return parts.join(' · ');
	}

	async function rerun(entry: HistoryEntry) {
		connections.setActive(entry.connection_id);
		schema.clear();
		await schema.load(entry.connection_id);

		hydrateQueryFromSpec(entry.spec, schema.tables, query);
		goto('/builder');
	}

	async function saveAsQuery(entry: HistoryEntry) {
		const name = prompt('Name this query:', `${entry.connection_name} query`);
		if (!name) return;
		await savedQueries.save({
			id: crypto.randomUUID(),
			name,
			connection_id: entry.connection_id,
			spec: entry.spec,
			created_at: Date.now().toString()
		});
	}

	async function handleClear() {
		if (!confirm('Clear all query history?')) return;
		await history.clear();
	}
</script>

<div class="flex flex-1 flex-col overflow-hidden">
	<div class="flex items-center justify-between border-b border-slate-200 px-8 py-5 dark:border-slate-800">
		<div>
			<h1 class="text-xl font-semibold tracking-tight">History</h1>
			<p class="mt-1 text-sm text-slate-500 dark:text-slate-400">Your recent and saved queries.</p>
		</div>
		{#if history.entries.length > 0}
			<button
				type="button"
				onclick={handleClear}
				class="rounded-lg border border-slate-200 px-3 py-2 text-sm font-medium text-slate-600 transition-colors hover:bg-slate-50 dark:border-slate-700 dark:text-slate-400 dark:hover:bg-slate-800"
			>
				Clear History
			</button>
		{/if}
	</div>

	<div class="flex-1 overflow-y-auto p-8">
		{#if history.loading}
			<div class="flex h-64 items-center justify-center">
				<div class="h-6 w-6 animate-spin rounded-full border-2 border-slate-200 border-t-slate-700 dark:border-slate-700 dark:border-t-slate-300"></div>
			</div>
		{:else if history.entries.length === 0}
			<div class="flex h-64 items-center justify-center">
				<div class="text-center">
					<div class="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-slate-100 text-slate-400 dark:bg-slate-800">
						<svg class="h-7 w-7" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 8v4l3 2M12 22a10 10 0 1 1 0-20 10 10 0 0 1 0 20z" /></svg>
					</div>
					<p class="mt-4 text-sm font-medium text-slate-600 dark:text-slate-300">No query history yet</p>
					<p class="mt-1 text-sm text-slate-400">Run your first query to see it here.</p>
					<a href="/builder" class="mt-5 inline-block rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-slate-700 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-300">
						Build a Query
					</a>
				</div>
			</div>
		{:else}
			<div class="space-y-3">
				{#each history.entries as entry (entry.id)}
					<div class="group rounded-xl border border-slate-200 bg-white p-4 transition-all hover:border-slate-300 hover:shadow-sm dark:border-slate-800 dark:bg-slate-900">
						<div class="flex items-start justify-between gap-4">
							<div class="flex-1 min-w-0">
								<div class="flex items-center gap-2">
									<span class="text-sm font-semibold text-slate-900 dark:text-slate-100">{entry.connection_name}</span>
									{#if entry.error}
										<span class="flex items-center gap-1 rounded bg-red-50 px-1.5 py-0.5 text-[10px] font-medium text-red-600 dark:bg-red-900/30 dark:text-red-400">failed</span>
									{:else}
										<span class="flex items-center gap-1 rounded bg-emerald-50 px-1.5 py-0.5 text-[10px] font-medium text-emerald-600 dark:bg-emerald-900/30 dark:text-emerald-400">{entry.row_count} rows</span>
									{/if}
									<span class="text-xs text-slate-400">{formatTime(entry.timestamp)}</span>
								</div>
								<p class="mt-1.5 text-xs text-slate-500 dark:text-slate-400">{summarizeSpec(entry)}</p>
								{#if entry.error}
									<p class="mt-1.5 break-all text-xs text-red-500">{entry.error}</p>
								{/if}
								{#if entry.warnings.length > 0}
									<div class="mt-1.5 flex flex-wrap gap-1">
										{#each entry.warnings.slice(0, 2) as w (w)}
											<span class="rounded bg-orange-50 px-1.5 py-0.5 text-[10px] text-orange-600 dark:bg-orange-900/30 dark:text-orange-400">{w}</span>
										{/each}
										{#if entry.warnings.length > 2}
											<span class="text-[10px] text-slate-400">+{entry.warnings.length - 2} more</span>
										{/if}
									</div>
								{/if}
							</div>

							<div class="flex shrink-0 gap-1.5 opacity-0 transition-opacity group-hover:opacity-100">
								{#if !entry.error}
									<button
										type="button"
										onclick={() => saveAsQuery(entry)}
										class="flex h-8 w-8 items-center justify-center rounded-lg border border-slate-200 text-slate-400 transition-colors hover:bg-slate-50 hover:text-slate-700 dark:border-slate-700 dark:hover:bg-slate-800 dark:hover:text-slate-200"
										aria-label="Save as query"
										title="Save as query"
									>
										<svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2zM17 21v-8H7v8M7 3v5h8" /></svg>
									</button>
								{/if}
								<button
									type="button"
									onclick={() => rerun(entry)}
									class="flex h-8 items-center gap-1.5 rounded-lg bg-slate-900 px-3 text-xs font-medium text-white transition-colors hover:bg-slate-700 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-300"
								>
									<svg class="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M5 3l14 9-14 9V3z" /></svg>
									Rerun
								</button>
							</div>
						</div>
					</div>
				{/each}
			</div>
		{/if}
	</div>
</div>
