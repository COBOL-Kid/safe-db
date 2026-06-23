<script lang="ts">
	import { DIALECTS, type ConnectionDef, type Dialect } from '$lib/ir';
	import * as api from '$lib/api';

	let {
		onSaved,
		onCancel
	}: { onSaved: () => void; onCancel: () => void } = $props();

	let name = $state('');
	let dialect = $state<Dialect>('Postgres');
	let host = $state('localhost');
	let port = $state(5432);
	let database = $state('');
	let username = $state('');
	let password = $state('');

	let testing = $state(false);
	let saving = $state(false);
	let testResult = $state<string | null>(null);
	let testError = $state<string | null>(null);
	let formError = $state<string | null>(null);

	function selectDialect(d: Dialect) {
		dialect = d;
		const entry = DIALECTS.find((x) => x.value === d);
		if (entry) port = entry.defaultPort;
	}

	function buildDef(): ConnectionDef {
		return {
			id: crypto.randomUUID(),
			name: name.trim() || `${dialect} ${host}:${port}`,
			dialect,
			host: host.trim(),
			port,
			database: database.trim(),
			username: username.trim()
		};
	}

	async function handleTest() {
		testing = true;
		testResult = null;
		testError = null;
		try {
			const def = buildDef();
			const version = await api.testConnection(def, password);
			testResult = version;
		} catch (e) {
			testError = String(e);
		} finally {
			testing = false;
		}
	}

	async function handleSave() {
		saving = true;
		formError = null;
		try {
			const def = buildDef();
			await api.saveConnection(def, password || null);
			onSaved();
		} catch (e) {
			formError = String(e);
		} finally {
			saving = false;
		}
	}
</script>

<div class="mx-auto w-full max-w-xl rounded-xl border border-slate-200 bg-white p-6">
	<h2 class="text-lg font-semibold text-slate-900">New Connection</h2>
	<p class="mt-1 text-sm text-slate-500">Connect to a production database. Credentials are stored in your OS keychain.</p>

	<div class="mt-6 space-y-4">
		<div>
			<label class="mb-1.5 block text-sm font-medium text-slate-700" for="cf-name">Name</label>
			<input
				id="cf-name"
				type="text"
				placeholder="My Production DB"
				bind:value={name}
				class="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-900 outline-none transition-colors focus:border-slate-400"
			/>
		</div>

		<div>
			<span class="mb-1.5 block text-sm font-medium text-slate-700" id="cf-dialect-label">Database type</span>
			<div class="flex gap-2" role="group" aria-labelledby="cf-dialect-label">
				{#each DIALECTS as d (d.value)}
					<button
						type="button"
						onclick={() => selectDialect(d.value)}
						class="flex-1 rounded-lg border px-3 py-2 text-sm font-medium transition-colors
							{dialect === d.value
							? 'border-slate-900 bg-slate-900 text-white'
							: 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50'}"
					>
						{d.label}
					</button>
				{/each}
			</div>
		</div>

		<div class="grid grid-cols-3 gap-3">
			<div class="col-span-2">
				<label class="mb-1.5 block text-sm font-medium text-slate-700" for="cf-host">Host</label>
				<input
					id="cf-host"
					type="text"
					placeholder="localhost"
					bind:value={host}
					class="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-900 outline-none transition-colors focus:border-slate-400"
				/>
			</div>
			<div>
				<label class="mb-1.5 block text-sm font-medium text-slate-700" for="cf-port">Port</label>
				<input
					id="cf-port"
					type="number"
					bind:value={port}
					class="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-900 outline-none transition-colors focus:border-slate-400"
				/>
			</div>
		</div>

		<div>
			<label class="mb-1.5 block text-sm font-medium text-slate-700" for="cf-db">Database</label>
			<input
				id="cf-db"
				type="text"
				placeholder="mydb"
				bind:value={database}
				class="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-900 outline-none transition-colors focus:border-slate-400"
			/>
		</div>

		<div class="grid grid-cols-2 gap-3">
			<div>
				<label class="mb-1.5 block text-sm font-medium text-slate-700" for="cf-user">Username</label>
				<input
					id="cf-user"
					type="text"
					placeholder="readonly"
					bind:value={username}
					class="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-900 outline-none transition-colors focus:border-slate-400"
				/>
			</div>
			<div>
				<label class="mb-1.5 block text-sm font-medium text-slate-700" for="cf-pw">Password</label>
				<input
					id="cf-pw"
					type="password"
					placeholder="••••••••"
					bind:value={password}
					class="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-900 outline-none transition-colors focus:border-slate-400"
				/>
			</div>
		</div>

		<div class="flex items-center gap-2 rounded-lg bg-amber-50 px-3 py-2.5 text-sm text-amber-700">
			<svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 9v4M12 17h.01M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" /></svg>
			<span>For best safety, connect as a dedicated <strong>read-only</strong> database role.</span>
		</div>

		{#if testResult}
			<div class="flex items-center gap-2 rounded-lg bg-emerald-50 px-3 py-2.5 text-sm text-emerald-700">
				<svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5L20 7" /></svg>
				<span class="truncate">Connected — {testResult}</span>
			</div>
		{/if}
		{#if testError}
			<div class="flex items-start gap-2 rounded-lg bg-red-50 px-3 py-2.5 text-sm text-red-700">
				<svg class="mt-0.5 h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
				<span class="break-all">{testError}</span>
			</div>
		{/if}
		{#if formError}
			<div class="rounded-lg bg-red-50 px-3 py-2.5 text-sm text-red-700">{formError}</div>
		{/if}
	</div>

	<div class="mt-6 flex items-center justify-between">
		<button
			type="button"
			onclick={handleTest}
			disabled={testing || saving}
			class="rounded-lg border border-slate-200 bg-white px-4 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-50 disabled:opacity-50"
		>
			{testing ? 'Testing…' : 'Test Connection'}
		</button>
		<div class="flex gap-2">
			<button
				type="button"
				onclick={onCancel}
				class="rounded-lg px-4 py-2 text-sm font-medium text-slate-500 transition-colors hover:text-slate-700"
			>
				Cancel
			</button>
			<button
				type="button"
				onclick={handleSave}
				disabled={saving || testing}
				class="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-slate-700 disabled:opacity-50"
			>
				{saving ? 'Saving…' : 'Save Connection'}
			</button>
		</div>
	</div>
</div>
