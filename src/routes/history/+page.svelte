<script lang="ts">
	import { history } from '$lib/stores/history.svelte';
	import { connections } from '$lib/stores/connections.svelte';
	import { query } from '$lib/stores/query.svelte';
	import { schema } from '$lib/stores/schema.svelte';
	import { savedQueries } from '$lib/stores/saved-queries.svelte';
	import { browser } from '$app/environment';
	import { goto } from '$app/navigation';
	import { hydrateQueryFromSpec, formatHydrationWarning } from '$lib/hydrate-query';
	import { formatTime, summarizeSpec } from '$lib/format';
	import ConfirmDialog from '$lib/components/ConfirmDialog.svelte';
	import PromptDialog from '$lib/components/PromptDialog.svelte';
	import type { HistoryEntry } from '$lib/ir';

	let showClearConfirm = $state(false);
	let showSavePrompt = $state(false);
	let saveQueryName = $state('');
	let saveEntry = $state<HistoryEntry | null>(null);

	$effect(() => {
		if (browser) history.load();
	});

	async function rerun(entry: HistoryEntry) {
		if (!(await connections.activate(entry.connection_id))) return;

		const hydration = hydrateQueryFromSpec(entry.spec, schema.tables, query);
		query.hydrationWarning = formatHydrationWarning(hydration);
		goto('/builder');
	}

	function openSavePrompt(entry: HistoryEntry) {
		saveEntry = entry;
		saveQueryName = `${entry.connection_name} query`;
		showSavePrompt = true;
	}

	async function confirmSaveQuery() {
		if (!saveEntry || !saveQueryName.trim()) return;
		await savedQueries.save({
			id: crypto.randomUUID(),
			name: saveQueryName.trim(),
			connection_id: saveEntry.connection_id,
			spec: saveEntry.spec,
			created_at: Math.floor(Date.now() / 1000).toString()
		});
		showSavePrompt = false;
		saveEntry = null;
	}

	async function handleClear() {
		showClearConfirm = true;
	}

	async function confirmClear() {
		showClearConfirm = false;
		await history.clear();
	}
</script>

<ConfirmDialog
	open={showClearConfirm}
	title="Clear history?"
	message="Clear all query history? This cannot be undone."
	destructive
	onConfirm={confirmClear}
	onCancel={() => (showClearConfirm = false)}
/>

<PromptDialog
	bind:open={showSavePrompt}
	bind:value={saveQueryName}
	title="Save query"
	message="Choose a name for this query."
	placeholder="Query name"
	confirmLabel="Save"
	onConfirm={confirmSaveQuery}
	onCancel={() => {
		showSavePrompt = false;
		saveEntry = null;
	}}
/>

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
										onclick={() => openSavePrompt(entry)}
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
