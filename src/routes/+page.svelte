<script lang="ts">
	import { savedQueries } from '$lib/stores/saved-queries.svelte';
	import { connections } from '$lib/stores/connections.svelte';
	import { schema } from '$lib/stores/schema.svelte';
	import { query } from '$lib/stores/query.svelte';
	import { browser } from '$app/environment';
	import { goto } from '$app/navigation';
	import { hydrateQueryFromSpec, formatHydrationWarning } from '$lib/hydrate-query';
	import type { SavedQuery } from '$lib/ir';
	import ConfirmDialog from '$lib/components/ConfirmDialog.svelte';

	let deleteTargetId = $state<string | null>(null);

	const actions = [
		{ href: '/connections', title: 'New Connection', desc: 'Connect to a database', icon: 'M12 5v14M5 12h14' },
		{ href: '/builder', title: 'Build a Query', desc: 'Visually explore your data', icon: 'M4 6h16M4 12h16M4 18h10' },
		{ href: '/history', title: 'Recent Queries', desc: 'Revisit past explorations', icon: 'M12 8v4l3 2M12 22a10 10 0 1 1 0-20 10 10 0 0 1 0 20z' }
	];

	$effect(() => {
		if (browser) {
			savedQueries.load();
			connections.load();
		}
	});

	function connName(id: string): string {
		return connections.connections.find((c) => c.id === id)?.name ?? 'Unknown';
	}

	async function loadSaved(sq: SavedQuery) {
		connections.setActive(sq.connection_id);
		schema.clear();
		await schema.load(sq.connection_id);

		const hydration = hydrateQueryFromSpec(sq.spec, schema.tables, query);
		query.hydrationWarning = formatHydrationWarning(hydration);
		goto('/builder');
	}

	function requestDeleteSaved(id: string) {
		deleteTargetId = id;
	}

	async function confirmDeleteSaved() {
		if (deleteTargetId) {
			await savedQueries.remove(deleteTargetId);
			deleteTargetId = null;
		}
	}
</script>

<ConfirmDialog
	open={deleteTargetId !== null}
	title="Delete saved query?"
	message="This saved query will be permanently removed."
	destructive
	onConfirm={confirmDeleteSaved}
	onCancel={() => (deleteTargetId = null)}
/>

<div class="flex flex-1 flex-col overflow-y-auto">
	<div class="mx-auto w-full max-w-4xl px-8 py-12">
		<h1 class="text-3xl font-bold tracking-tight">Welcome to safe-db</h1>
		<p class="mt-2 text-base text-slate-500 dark:text-slate-400">
			Safely explore production databases with non-locking reads and enforced best practices.
		</p>

		<div class="mt-10 grid grid-cols-1 gap-4 sm:grid-cols-3">
			{#each actions as action (action.href)}
				<a
					href={action.href}
					class="group flex flex-col rounded-xl border border-slate-200 bg-white p-5 transition-all hover:border-slate-300 hover:shadow-md dark:border-slate-800 dark:bg-slate-900 dark:hover:border-slate-700"
				>
					<div
						class="flex h-10 w-10 items-center justify-center rounded-lg bg-slate-100 text-slate-600 transition-colors group-hover:bg-slate-900 group-hover:text-white dark:bg-slate-800 dark:text-slate-400 dark:group-hover:bg-slate-100 dark:group-hover:text-slate-900"
					>
						<svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d={action.icon} /></svg>
					</div>
					<h3 class="mt-4 text-sm font-semibold">{action.title}</h3>
					<p class="mt-1 text-sm text-slate-500 dark:text-slate-400">{action.desc}</p>
				</a>
			{/each}
		</div>

		{#if savedQueries.queries.length > 0}
			<div class="mt-10">
				<h2 class="text-sm font-semibold text-slate-500 dark:text-slate-400">Saved Queries</h2>
				<div class="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
					{#each savedQueries.queries as sq (sq.id)}
						<div class="group flex items-center justify-between rounded-xl border border-slate-200 bg-white p-4 transition-all hover:border-slate-300 hover:shadow-sm dark:border-slate-800 dark:bg-slate-900">
							<button type="button" onclick={() => loadSaved(sq)} class="flex-1 text-left min-w-0">
								<p class="text-sm font-semibold truncate">{sq.name}</p>
								<p class="mt-0.5 text-xs text-slate-400">{connName(sq.connection_id)} · {sq.spec.tables.length} table{sq.spec.tables.length !== 1 ? 's' : ''}</p>
							</button>
							<button
								type="button"
								onclick={() => requestDeleteSaved(sq.id)}
								class="ml-2 flex h-7 w-7 shrink-0 items-center justify-center rounded-lg text-slate-300 opacity-0 transition-all hover:text-red-500 group-hover:opacity-100"
								aria-label="Delete saved query"
							>
								<svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" /></svg>
							</button>
						</div>
					{/each}
				</div>
			</div>
		{/if}

		<div class="mt-12 rounded-xl border border-slate-200 bg-white p-6 dark:border-slate-800 dark:bg-slate-900">
			<h2 class="text-sm font-semibold">How safe-db protects your database</h2>
			<div class="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
				<div class="flex gap-3">
					<div class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-emerald-600 dark:bg-emerald-900/40 dark:text-emerald-400">
						<svg class="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5L20 7" /></svg>
					</div>
					<div>
						<p class="text-sm font-medium">Non-locking reads</p>
						<p class="text-sm text-slate-400">Dirty-read tolerant isolation — never blocks production writes.</p>
					</div>
				</div>
				<div class="flex gap-3">
					<div class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-emerald-600 dark:bg-emerald-900/40 dark:text-emerald-400">
						<svg class="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5L20 7" /></svg>
					</div>
					<div>
						<p class="text-sm font-medium">Indexed joins only</p>
						<p class="text-sm text-slate-400">Joins on non-indexed columns are rejected before execution.</p>
					</div>
				</div>
				<div class="flex gap-3">
					<div class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-emerald-600 dark:bg-emerald-900/40 dark:text-emerald-400">
						<svg class="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5L20 7" /></svg>
					</div>
					<div>
						<p class="text-sm font-medium">Capped row limits & timeouts</p>
						<p class="text-sm text-slate-400">Every query is bounded — no runaway full-table scans.</p>
					</div>
				</div>
				<div class="flex gap-3">
					<div class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-emerald-600 dark:bg-emerald-900/40 dark:text-emerald-400">
						<svg class="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5L20 7" /></svg>
					</div>
					<div>
						<p class="text-sm font-medium">Read-only by construction</p>
						<p class="text-sm text-slate-400">Writes are structurally impossible — no SQL injection surface.</p>
					</div>
				</div>
			</div>
		</div>
	</div>
</div>
