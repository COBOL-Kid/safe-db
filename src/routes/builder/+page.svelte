<script lang="ts">
	import { connections } from '$lib/stores/connections.svelte';
	import { schema } from '$lib/stores/schema.svelte';
	import SchemaBrowser from '$lib/components/SchemaBrowser.svelte';
	import { browser } from '$app/environment';

	$effect(() => {
		if (browser && connections.activeId && !schema.schema && !schema.loading) {
			schema.load(connections.activeId);
		}
	});

	const dialectLabels: Record<string, string> = {
		Postgres: 'PostgreSQL',
		MySql: 'MySQL'
	};
</script>

<div class="flex flex-1 flex-col overflow-hidden">
	<div class="flex items-center justify-between border-b border-slate-200 px-8 py-4">
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
		<button
			class="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-slate-700 disabled:opacity-50"
			disabled={!connections.activeId}
		>
			Run Query
		</button>
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
		<div class="flex flex-1 overflow-hidden">
			<aside class="w-72 shrink-0 border-r border-slate-200 bg-white">
				<SchemaBrowser />
			</aside>
			<div class="flex flex-1 items-center justify-center bg-slate-50">
				<div class="text-center">
					<div class="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-white text-slate-300 shadow-sm">
						<svg class="h-7 w-7" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M3 3v18h18M7 14l4-4 4 4 5-5" /></svg>
					</div>
					<p class="mt-4 text-sm font-medium text-slate-500">Query canvas</p>
					<p class="mt-1 text-sm text-slate-400">Click a table in the sidebar to start building.</p>
				</div>
			</div>
		</div>
	{/if}
</div>
