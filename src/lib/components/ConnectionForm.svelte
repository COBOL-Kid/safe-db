<script lang="ts">
	import {
		DIALECTS,
		type ConnectionDef,
		type Dialect,
		type TransportSecurity,
		type TransportSecurityMode
	} from '$lib/ir';
	import * as api from '$lib/api';
	import ConnectionAdvancedPanel from '$lib/components/ConnectionAdvancedPanel.svelte';
	import {
		inferLocation,
		isLocalHost,
		securityLabelForMode,
		transportPresetForLocation,
		type DatabaseLocation
	} from '$lib/connection-presets';
	import { ConnectionStringParseError, parseConnectionString } from '$lib/parse-connection-string';
	import { classifyConnectionError } from '$lib/ssl-errors';

	let {
		onSaved,
		onCancel
	}: { onSaved: () => void; onCancel: () => void } = $props();

	type EntryPath = 'unset' | 'string' | 'guided';
	type FormStep = 'choose' | 'string_input' | 'location' | 'credentials';

	let entryPath = $state<EntryPath>('unset');
	let formStep = $state<FormStep>('choose');
	let location = $state<DatabaseLocation | null>(null);
	let parsedFromString = $state(false);
	let connectionString = $state('');
	let parseError = $state<string | null>(null);
	let parseWarnings = $state<string[]>([]);
	let transportOverridden = $state(false);
	let portIsAuto = $state(true);

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

	let selectedDialect = $derived(DIALECTS.find((x) => x.value === dialect));
	let securityLabel = $derived(securityLabelForMode(transportMode));
	let errorClassification = $derived(
		testError
			? classifyConnectionError(testError, {
					location,
					remoteHost: !isLocalHost(host)
				})
			: null
	);

	const locationCards: {
		id: DatabaseLocation;
		title: string;
		subtitle: string;
	}[] = [
		{ id: 'local', title: 'On this computer', subtitle: 'Local development or testing' },
		{ id: 'cloud', title: 'Online or in the cloud', subtitle: 'AWS, Google, Supabase, etc.' },
		{ id: 'organization', title: 'From my organization', subtitle: 'Work or school database' }
	];

	function resetResultState() {
		testResult = null;
		testError = null;
		formError = null;
	}

	function choosePath(path: Exclude<EntryPath, 'unset'>) {
		entryPath = path;
		formStep = path === 'string' ? 'string_input' : 'location';
		resetResultState();
	}

	function switchToGuided() {
		entryPath = 'guided';
		formStep = 'location';
		parseError = null;
	}

	function switchToString() {
		entryPath = 'string';
		formStep = 'string_input';
		parseError = null;
	}

	function applyTransportSecurity(security: TransportSecurity) {
		transportMode = security.mode;
		caPem = security.ca_pem ?? '';
		oracleWalletLocation = security.oracle_wallet_location ?? '';
		insecureAcknowledged = security.insecure_acknowledged;
	}

	function applyLocationPreset(nextLocation: DatabaseLocation) {
		location = nextLocation;
		transportOverridden = false;
		applyTransportSecurity(transportPresetForLocation(nextLocation));
		formStep = 'credentials';
		resetResultState();
	}

	function reapplyRecommendedSettings() {
		if (!location) return;
		transportOverridden = false;
		applyTransportSecurity(transportPresetForLocation(location));
		resetResultState();
	}

	function markTransportManual() {
		transportOverridden = true;
		resetResultState();
	}

	function selectDialect(d: Dialect) {
		const previous = selectedDialect;
		dialect = d;
		const entry = DIALECTS.find((x) => x.value === d);
		if (entry && (portIsAuto || port === previous?.defaultPort)) {
			port = entry.defaultPort;
			portIsAuto = true;
		}
		resetResultState();
	}

	function handlePortInput() {
		portIsAuto = false;
		resetResultState();
	}

	function handleHostInput(nextHost: string) {
		if (!transportOverridden) {
			if (location === 'local' && !isLocalHost(nextHost)) {
				location = 'cloud';
				applyTransportSecurity(transportPresetForLocation('cloud'));
			} else if (entryPath === 'string') {
				location = inferLocation(nextHost);
			}
		}
		resetResultState();
	}

	function applyParsedInput() {
		parseError = null;
		parseWarnings = [];
		resetResultState();

		try {
			const parsed = parseConnectionString(connectionString);
			parsedFromString = true;
			dialect = parsed.dialect;
			host = parsed.host;
			port = parsed.port;
			portIsAuto = true;
			database = parsed.database;
			username = parsed.username;
			password = parsed.password ?? '';
			applyTransportSecurity(parsed.transport_security);
			transportOverridden = false;
			location = parsed.inferredLocation;
			parseWarnings = parsed.warnings;
			connectionString = parsed.sanitizedInput;
			formStep = 'credentials';
		} catch (error) {
			parseError =
				error instanceof ConnectionStringParseError
					? error.message
					: 'This connection string could not be parsed.';
		}
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
				insecure_acknowledged: insecureAcknowledged,
				legacy_implicit: false
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

<div class="mx-auto w-full max-w-2xl rounded-xl border border-slate-200 bg-white p-6 dark:border-slate-700 dark:bg-slate-900">
	<div class="flex items-start justify-between gap-4">
		<div>
			<h2 class="text-lg font-semibold text-slate-900 dark:text-slate-100">New Connection</h2>
			<p class="mt-1 text-sm text-slate-500 dark:text-slate-400">Connect to a database. Credentials are stored in your OS keychain.</p>
		</div>
		{#if formStep !== 'choose'}
			<button
				type="button"
				onclick={() => {
					entryPath = 'unset';
					formStep = 'choose';
				}}
				class="text-sm font-medium text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200"
			>
				Change path
			</button>
		{/if}
	</div>

	<div class="mt-6 space-y-5">
		{#if formStep === 'choose'}
			<div class="grid gap-3 sm:grid-cols-2">
				<button
					type="button"
					onclick={() => choosePath('string')}
					class="rounded-lg border border-slate-200 bg-white p-4 text-left transition-colors hover:border-slate-400 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:hover:border-slate-500 dark:hover:bg-slate-800"
				>
					<span class="block text-sm font-semibold text-slate-900 dark:text-slate-100">I have a connection string</span>
					<span class="mt-1 block text-sm text-slate-500 dark:text-slate-400">Paste from your host or dashboard</span>
				</button>
				<button
					type="button"
					onclick={() => choosePath('guided')}
					class="rounded-lg border border-slate-200 bg-white p-4 text-left transition-colors hover:border-slate-400 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:hover:border-slate-500 dark:hover:bg-slate-800"
				>
					<span class="block text-sm font-semibold text-slate-900 dark:text-slate-100">Help me set it up</span>
					<span class="mt-1 block text-sm text-slate-500 dark:text-slate-400">Local, cloud, or work database</span>
				</button>
			</div>
		{:else if formStep === 'string_input'}
			<div class="space-y-4">
				<div>
					<label class="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300" for="cf-connection-string">Connection string</label>
					<textarea
						id="cf-connection-string"
						bind:value={connectionString}
						rows="5"
						spellcheck="false"
						class="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 font-mono text-xs text-slate-900 outline-none transition-colors placeholder:text-slate-400 focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:placeholder:text-slate-500 dark:focus:border-slate-500"
						placeholder="postgresql://readonly:password@host:5432/database"
					></textarea>
				</div>

				{#if parseError}
					<div class="rounded-lg bg-red-50 px-3 py-2.5 text-sm text-red-700 dark:bg-red-900/20 dark:text-red-300">
						{parseError}
						<button type="button" onclick={switchToGuided} class="ml-1 font-medium underline">Use guided setup</button>
					</div>
				{/if}

				<div class="flex items-center justify-between">
					<button
						type="button"
						onclick={switchToGuided}
						class="text-sm font-medium text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200"
					>
						Use guided setup
					</button>
					<button
						type="button"
						onclick={applyParsedInput}
						class="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-slate-700 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-300"
					>
						Continue
					</button>
				</div>
			</div>
		{:else if formStep === 'location'}
			<div class="space-y-4">
				<div>
					<span class="mb-2 block text-sm font-medium text-slate-700 dark:text-slate-300">Database location</span>
					<div class="grid gap-3 sm:grid-cols-3">
						{#each locationCards as card (card.id)}
							<button
								type="button"
								onclick={() => applyLocationPreset(card.id)}
								class="rounded-lg border p-4 text-left transition-colors {location === card.id
									? 'border-slate-900 bg-slate-900 text-white dark:border-slate-100 dark:bg-slate-100 dark:text-slate-900'
									: 'border-slate-200 bg-white text-slate-600 hover:border-slate-400 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:border-slate-500 dark:hover:bg-slate-800'}"
							>
								<span class="block text-sm font-semibold">{card.title}</span>
								<span class="mt-1 block text-xs opacity-75">{card.subtitle}</span>
							</button>
						{/each}
					</div>
				</div>
				<button
					type="button"
					onclick={switchToString}
					class="text-sm font-medium text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200"
				>
					I have a connection string
				</button>
			</div>
		{:else}
			{#if parsedFromString}
				<div class="rounded-lg border border-slate-200 bg-slate-50 p-3 dark:border-slate-700 dark:bg-slate-800/40">
					<div class="flex flex-wrap gap-2">
						<span class="rounded-full bg-white px-2.5 py-1 text-xs font-medium text-slate-600 ring-1 ring-slate-200 dark:bg-slate-900 dark:text-slate-300 dark:ring-slate-700">{selectedDialect?.label ?? dialect}</span>
						<span class="rounded-full bg-white px-2.5 py-1 text-xs font-medium text-slate-600 ring-1 ring-slate-200 dark:bg-slate-900 dark:text-slate-300 dark:ring-slate-700">{host}:{port}</span>
						<span class="rounded-full bg-white px-2.5 py-1 text-xs font-medium text-slate-600 ring-1 ring-slate-200 dark:bg-slate-900 dark:text-slate-300 dark:ring-slate-700">{database || 'No database'}</span>
						<span class="rounded-full bg-white px-2.5 py-1 text-xs font-medium text-slate-600 ring-1 ring-slate-200 dark:bg-slate-900 dark:text-slate-300 dark:ring-slate-700">{securityLabel.text}</span>
					</div>
					{#if parseWarnings.length > 0}
						<div class="mt-3 space-y-1 text-sm text-amber-700 dark:text-amber-300">
							{#each parseWarnings as warning (warning)}
								<p>{warning}</p>
							{/each}
						</div>
					{/if}
				</div>
			{/if}

			{#if location === 'local' && !isLocalHost(host)}
				<div class="rounded-lg bg-amber-50 px-3 py-2.5 text-sm text-amber-700 dark:bg-amber-900/20 dark:text-amber-300">
					This host is not local, so recommended security has been switched to cloud defaults.
					<button type="button" onclick={() => applyLocationPreset('cloud')} class="ml-1 font-medium underline">Apply cloud defaults</button>
				</div>
			{/if}

			<div class="space-y-4">
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
							oninput={(event) => handleHostInput(event.currentTarget.value)}
							class="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none transition-colors placeholder:text-slate-400 focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:placeholder:text-slate-500 dark:focus:border-slate-500"
						/>
					</div>
					<div>
						<label class="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300" for="cf-port">Port</label>
						<input
							id="cf-port"
							type="number"
							bind:value={port}
							oninput={handlePortInput}
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
						oninput={resetResultState}
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
							oninput={resetResultState}
							class="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none transition-colors placeholder:text-slate-400 focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:placeholder:text-slate-500 dark:focus:border-slate-500"
						/>
					</div>
					<div>
						<label class="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300" for="cf-pw">Password</label>
						<div class="relative">
							<input
								id="cf-pw"
								type={showPassword ? 'text' : 'password'}
								placeholder="Password"
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

				{#if dialect === 'Oracle' && transportMode !== 'Disabled'}
					<div>
						<label class="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300" for="cf-wallet">Oracle wallet location</label>
						<input
							id="cf-wallet"
							type="text"
							bind:value={oracleWalletLocation}
							oninput={resetResultState}
							class="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
						/>
					</div>
				{/if}

				{#if transportMode === 'EncryptOnly' || transportMode === 'Disabled'}
					<label class="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-800 dark:border-red-900/50 dark:bg-red-900/20 dark:text-red-300">
						<input type="checkbox" bind:checked={insecureAcknowledged} class="mt-0.5" />
						<span>I understand this setting weakens protection against interception and server impersonation.</span>
					</label>
				{/if}

				<div class="flex flex-wrap items-center gap-2 rounded-lg bg-slate-50 px-3 py-2.5 text-sm text-slate-600 dark:bg-slate-800/60 dark:text-slate-300">
					<span class="h-2 w-2 rounded-full {securityLabel.tone === 'success' ? 'bg-emerald-500' : securityLabel.tone === 'warning' ? 'bg-amber-500' : 'bg-red-500'}"></span>
					<span>{securityLabel.text}</span>
					{#if transportOverridden && location}
						<button type="button" onclick={reapplyRecommendedSettings} class="font-medium underline">Reapply recommended settings</button>
					{/if}
				</div>

				<ConnectionAdvancedPanel
					{dialect}
					bind:transportMode
					bind:caPem
					bind:oracleWalletLocation
					bind:insecureAcknowledged
					onManualChange={markTransportManual}
				/>

				<div class="flex items-center gap-2 rounded-lg bg-amber-50 px-3 py-2.5 text-sm text-amber-700 dark:bg-amber-900/20 dark:text-amber-300">
					<svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 9v4M12 17h.01M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" /></svg>
					<span>For best safety, connect as a dedicated <strong>read-only</strong> database role.</span>
				</div>

				{#if testResult}
					<div class="flex items-center gap-2 rounded-lg bg-emerald-50 px-3 py-2.5 text-sm text-emerald-700 dark:bg-emerald-900/20 dark:text-emerald-300">
						<svg class="h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5L20 7" /></svg>
						<span class="truncate">Connected - {testResult}</span>
					</div>
				{/if}
				{#if testError}
					<div class="space-y-3">
						<div class="flex items-start gap-2 rounded-lg bg-red-50 px-3 py-2.5 text-sm text-red-700 dark:bg-red-900/20 dark:text-red-300">
							<svg class="mt-0.5 h-4 w-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
							<span class="break-all">{testError}</span>
						</div>
						{#if errorClassification?.showTroubleshooting}
							<div class="rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm text-amber-800 dark:border-amber-900/50 dark:bg-amber-900/20 dark:text-amber-300">
								<p class="font-medium">Your organization may require a security file.</p>
								<p class="mt-1">Paste the CA certificate PEM below, then test again. This will use certificate verification.</p>
								<textarea
									aria-label="CA certificate PEM"
									bind:value={caPem}
									oninput={() => {
										transportMode = 'VerifyCa';
									}}
									rows="4"
									class="mt-3 w-full rounded-lg border border-amber-200 bg-white px-3 py-2 font-mono text-xs text-slate-900 dark:border-amber-900/60 dark:bg-slate-900 dark:text-slate-100"
								></textarea>
							</div>
						{/if}
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
					{testing ? 'Testing...' : 'Test Connection'}
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
						{saving ? 'Saving...' : 'Save Connection'}
					</button>
				</div>
			</div>
		{/if}
	</div>
</div>
