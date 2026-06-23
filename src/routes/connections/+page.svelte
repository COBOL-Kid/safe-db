<script lang="ts">
	import { connections } from '$lib/stores/connections.svelte';
	import { schema } from '$lib/stores/schema.svelte';
	import { browser } from '$app/environment';
	import ConnectionForm from '$lib/components/ConnectionForm.svelte';
	import { goto } from '$app/navigation';

	let showForm = $state(false);

	const dialectLabels: Record<string, string> = {
		Postgres: 'PostgreSQL',
		MySql: 'MySQL',
		Mssql: 'SQL Server',
		Oracle: 'Oracle'
	};

	async function handleSaved() {
		showForm = false;
		await connections.load();
	}

	async function handleDelete(id: string, name: string) {
		if (!confirm(`Delete connection "${name}"?`)) return;
		await connections.remove(id);
	}

	async function handleExplore(id: string) {
		connections.setActive(id);
		schema.clear();
		await schema.load(id);
		goto('/builder');
	}

	$effect(() => {
		if (browser) connections.load();
	});
</script>

<div class="flex flex-1 flex-col overflow-hidden">
	<div class="flex items-center justify-between border-b border-slate-200 px-8 py-5">
		<div>
			<h1 class="text-xl font-semibold tracking-tight text-slate-900">Connections</h1>
			<p class="mt-1 text-sm text-slate-500">Manage your database connections.</p>
		</div>
		{#if !showForm}
			<button
				type="button"
				onclick={() => (showForm = true)}
				class="flex items-center gap-2 rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-slate-700"
			>
				<svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14" /></svg>
				Add Connection
			</button>
		{/if}
	</div>

	<div class="flex-1 overflow-y-auto p-8">
		{#if showForm}
			<ConnectionForm onSaved={handleSaved} onCancel={() => (showForm = false)} />
		{:else if connections.loading}
			<div class="flex h-64 items-center justify-center">
				<div class="h-6 w-6 animate-spin rounded-full border-2 border-slate-200 border-t-slate-700"></div>
			</div>
		{:else if connections.error}
			<div class="rounded-lg bg-red-50 p-4 text-sm text-red-700">{connections.error}</div>
		{:else if connections.connections.length === 0}
			<div class="flex h-64 items-center justify-center">
				<div class="text-center">
					<div class="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-slate-100 text-slate-400">
						<svg class="h-7 w-7" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14" /></svg>
					</div>
					<p class="mt-4 text-sm font-medium text-slate-600">No connections yet</p>
					<p class="mt-1 text-sm text-slate-400">Add a connection to start exploring your data.</p>
					<button
						type="button"
						onclick={() => (showForm = true)}
						class="mt-5 rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-slate-700"
					>
						Add Connection
					</button>
				</div>
			</div>
		{:else}
			<div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
				{#each connections.connections as conn (conn.id)}
					<div class="group flex flex-col rounded-xl border border-slate-200 bg-white p-5 transition-all hover:border-slate-300 hover:shadow-md">
						<div class="flex items-start justify-between">
							<div class="flex items-center gap-2.5">
								<div class="flex h-9 w-9 items-center justify-center rounded-lg bg-slate-100 text-xs font-bold text-slate-600">
									{dialectLabels[conn.dialect]?.slice(0, 2) ?? 'DB'}
								</div>
								<div>
									<h3 class="text-sm font-semibold text-slate-900">{conn.name}</h3>
									<p class="text-xs text-slate-400">{dialectLabels[conn.dialect]}</p>
								</div>
							</div>
							<button
								type="button"
								onclick={() => handleDelete(conn.id, conn.name)}
								class="text-slate-300 opacity-0 transition-all hover:text-red-500 group-hover:opacity-100"
								aria-label="Delete connection"
							>
								<svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" /></svg>
							</button>
						</div>

						<div class="mt-4 space-y-1 text-xs text-slate-500">
							<p><span class="text-slate-400">Host</span> &nbsp;{conn.host}:{conn.port}</p>
							<p><span class="text-slate-400">DB</span> &nbsp;&nbsp;&nbsp;{conn.database}</p>
							<p><span class="text-slate-400">User</span> &nbsp;{conn.username}</p>
						</div>

						<button
							type="button"
							onclick={() => handleExplore(conn.id)}
							class="mt-5 flex items-center justify-center gap-1.5 rounded-lg bg-slate-900 px-3 py-2 text-sm font-medium text-white transition-colors hover:bg-slate-700"
						>
							Explore
							<svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14M12 5l7 7-7 7" /></svg>
						</button>
					</div>
				{/each}
			</div>
		{/if}
	</div>
</div>
