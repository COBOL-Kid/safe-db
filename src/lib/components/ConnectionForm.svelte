<script lang="ts">
	import {
		DIALECTS,
		type ConnectionDef,
		type Dialect,
		type TransportSecurityMode
	} from '$lib/ir';
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
	let showPassword = $state(false);
	let transportMode = $state<TransportSecurityMode>('VerifyIdentity');
	let caPem = $state('');
	let oracleWalletLocation = $state('');
	let insecureAcknowledged = $state(false);

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
			version: 2,
			id: crypto.randomUUID(),
			name: name.trim() || `${dialect} ${host}:${port}`,
			dialect,
			host: host.trim(),
			port,
			database: database.trim(),
			username: username.trim(),
			transport_security: {
				mode: transportMode,
				ca_pem: caPem.trim() || null,
				oracle_wallet_location: oracleWalletLocation.trim() || null,
				insecure_acknowledged: insecureAcknowledged
			}
		};
	}

	function validateForm(): string | null {
		if (!host.trim()) return 'Host is required';
		if (!database.trim()) return 'Database is required';
		if (!username.trim()) return 'Username is required';
		if (!Number.isFinite(port) || port < 1 || port > 65535) {
			return 'Port must be between 1 and 65535';
		}
		if (
			(transportMode === 'EncryptOnly' || transportMode === 'Disabled') &&
			!insecureAcknowledged
		) {
			return 'Acknowledge the insecure transport setting before continuing';
		}
		if (dialect === 'Oracle' && transportMode !== 'Disabled' && !oracleWalletLocation.trim()) {
			return 'Oracle TCPS requires a wallet location';
		}
		return null;
	}

	async function handleTest() {
		const validationError = validateForm();
		if (validationError) {
			testError = validationError;
			return;
		}
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
		const validationError = validateForm();
		if (validationError) {
			formError = validationError;
			return;
		}
		saving = true;
		formError = null;
		try {
			const def = buildDef();
			await api.saveConnection(def, password);
			password = '';
			showPassword = false;
			onSaved();
		} catch (e) {
			formError = String(e);
		} finally {
			saving = false;
		}
	}
</script>

<div class="mx-auto w-full max-w-xl rounded-xl border border-slate-200 bg-white p-6 dark:border-slate-700 dark:bg-slate-900">
	<h2 class="text-lg font-semibold text-slate-900 dark:text-slate-100">New Connection</h2>
	<p class="mt-1 text-sm text-slate-500 dark:text-slate-400">Connect to a production database. Credentials are stored in your OS keychain.</p>

	<div class="mt-6 space-y-4">
		<div>
			<label class="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300" for="cf-name">Name</label>
			<input
				id="cf-name"
				type="text"
				placeholder="My Production DB"
				bind:value={name}
				class="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none transition-colors placeholder:text-slate-400 focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:placeholder:text-slate-500 dark:focus:border-slate-500"
			/>
		</div>

		<div>
			<span class="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300" id="cf-dialect-label">Database type</span>
			<div class="grid grid-cols-2 gap-2" role="group" aria-labelledby="cf-dialect-label">
				{#each DIALECTS as d (d.value)}
					<button
						type="button"
						onclick={() => selectDialect(d.value)}
						class="rounded-lg border px-3 py-2 text-sm font-medium transition-colors
							{dialect === d.value
							? 'border-slate-900 bg-slate-900 text-white dark:border-slate-100 dark:bg-slate-100 dark:text-slate-900'
							: 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300 dark:hover:bg-slate-700'}"
					>
						{d.label}
					</button>
				{/each}
			</div>
		</div>

		<div class="grid grid-cols-3 gap-3">
			<div class="col-span-2">
				<label class="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300" for="cf-host">Host</label>
				<input
					id="cf-host"
					type="text"
					placeholder="localhost"
					bind:value={host}
					class="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none transition-colors placeholder:text-slate-400 focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:placeholder:text-slate-500 dark:focus:border-slate-500"
				/>
			</div>
			<div>
				<label class="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300" for="cf-port">Port</label>
				<input
					id="cf-port"
					type="number"
					bind:value={port}
					class="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none transition-colors focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:focus:border-slate-500"
				/>
			</div>
		</div>

		<div>
			<label class="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300" for="cf-db">Database</label>
			<input
				id="cf-db"
				type="text"
				placeholder="mydb"
				bind:value={database}
				class="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none transition-colors placeholder:text-slate-400 focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:placeholder:text-slate-500 dark:focus:border-slate-500"
			/>
		</div>

		<div class="grid grid-cols-2 gap-3">
			<div>
				<label class="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300" for="cf-user">Username</label>
				<input
					id="cf-user"
					type="text"
					placeholder="readonly"
					bind:value={username}
					class="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none transition-colors placeholder:text-slate-400 focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:placeholder:text-slate-500 dark:focus:border-slate-500"
				/>
			</div>
			<div>
				<label class="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300" for="cf-pw">Password</label>
				<div class="relative">
					<input
						id="cf-pw"
						type={showPassword ? 'text' : 'password'}
						placeholder="••••••••"
						bind:value={password}
						class="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 pr-10 text-sm text-slate-900 outline-none transition-colors placeholder:text-slate-400 focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:placeholder:text-slate-500 dark:focus:border-slate-500"
					/>
					<button
						type="button"
						onclick={() => (showPassword = !showPassword)}
						class="absolute right-1.5 top-1/2 flex h-7 w-7 -translate-y-1/2 items-center justify-center rounded text-slate-400 transition-colors hover:text-slate-600 dark:text-slate-500 dark:hover:text-slate-300"
						aria-label={showPassword ? 'Hide password' : 'Show password'}
						aria-pressed={showPassword}
					>
						{#if showPassword}
							<svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.94 10.94 0 0 1 12 20c-7 0-10-8-10-8a19.81 19.81 0 0 1 5.06-5.94M9.9 4.24A10.94 10.94 0 0 1 12 4c7 0 10 8 10 8a19.69 19.69 0 0 1-3.17 4.19M14.12 14.12A3 3 0 1 1 9.88 9.88" /><path d="M1 1l22 22" /></svg>
						{:else}
							<svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3-8 10-8 10 8 10 8-3 8-10 8-10-8-10-8z" /><circle cx="12" cy="12" r="3" /></svg>
						{/if}
					</button>
				</div>
			</div>
		</div>

		<div>
			<span class="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300">Transport security</span>
			<div class="grid grid-cols-2 gap-2" role="group" aria-label="Transport security">
				{#each [
					['VerifyIdentity', 'Verify identity'],
					['VerifyCa', 'Verify CA'],
					['EncryptOnly', 'Encrypt only'],
					['Disabled', 'Disabled']
				] as option (option[0])}
					<button
						type="button"
						onclick={() => {
							transportMode = option[0] as TransportSecurityMode;
							if (transportMode === 'VerifyIdentity' || transportMode === 'VerifyCa') {
								insecureAcknowledged = false;
							}
						}}
						class="rounded-lg border px-3 py-2 text-sm font-medium {transportMode === option[0]
							? 'border-slate-900 bg-slate-900 text-white dark:border-slate-100 dark:bg-slate-100 dark:text-slate-900'
							: 'border-slate-200 text-slate-600 dark:border-slate-700 dark:text-slate-300'}"
					>
						{option[1]}
					</button>
				{/each}
			</div>
		</div>

		{#if transportMode === 'VerifyCa'}
			<div>
				<label class="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300" for="cf-ca">CA certificate (PEM)</label>
				<textarea id="cf-ca" bind:value={caPem} rows="4" class="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 font-mono text-xs dark:border-slate-700 dark:bg-slate-800"></textarea>
			</div>
		{/if}

		{#if dialect === 'Oracle' && transportMode !== 'Disabled'}
			<div>
				<label class="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300" for="cf-wallet">Oracle wallet location</label>
				<input id="cf-wallet" type="text" bind:value={oracleWalletLocation} class="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-800" />
			</div>
		{/if}

		{#if transportMode === 'EncryptOnly' || transportMode === 'Disabled'}
			<label class="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-800 dark:border-red-900/50 dark:bg-red-900/20 dark:text-red-300">
				<input type="checkbox" bind:checked={insecureAcknowledged} class="mt-0.5" />
				<span>I understand this setting weakens protection against interception and server impersonation.</span>
			</label>
		{/if}

		<div class="flex items-center gap-2 rounded-lg bg-amber-50 px-3 py-2.5 text-sm text-amber-700 dark:bg-amber-900/20 dark:text-amber-300">
			<svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 9v4M12 17h.01M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" /></svg>
			<span>For best safety, connect as a dedicated <strong>read-only</strong> database role.</span>
		</div>

		{#if testResult}
			<div class="flex items-center gap-2 rounded-lg bg-emerald-50 px-3 py-2.5 text-sm text-emerald-700 dark:bg-emerald-900/20 dark:text-emerald-300">
				<svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5L20 7" /></svg>
				<span class="truncate">Connected — {testResult}</span>
			</div>
		{/if}
		{#if testError}
			<div class="flex items-start gap-2 rounded-lg bg-red-50 px-3 py-2.5 text-sm text-red-700 dark:bg-red-900/20 dark:text-red-300">
				<svg class="mt-0.5 h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
				<span class="break-all">{testError}</span>
			</div>
		{/if}
		{#if formError}
			<div class="rounded-lg bg-red-50 px-3 py-2.5 text-sm text-red-700 dark:bg-red-900/20 dark:text-red-300">{formError}</div>
		{/if}
	</div>

	<div class="mt-6 flex items-center justify-between">
		<button
			type="button"
			onclick={handleTest}
			disabled={testing || saving}
			class="rounded-lg border border-slate-200 bg-white px-4 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-50 disabled:opacity-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 dark:hover:bg-slate-700"
		>
			{testing ? 'Testing…' : 'Test Connection'}
		</button>
		<div class="flex gap-2">
			<button
				type="button"
				onclick={onCancel}
				class="rounded-lg px-4 py-2 text-sm font-medium text-slate-500 transition-colors hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200"
			>
				Cancel
			</button>
			<button
				type="button"
				onclick={handleSave}
				disabled={saving || testing}
				class="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-slate-700 disabled:opacity-50 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-300"
			>
				{saving ? 'Saving…' : 'Save Connection'}
			</button>
		</div>
	</div>
</div>
