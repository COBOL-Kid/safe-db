<script lang="ts">
	import type { Dialect, TransportSecurityMode } from '$lib/ir';

	let {
		dialect,
		transportMode = $bindable(),
		caPem = $bindable(),
		oracleWalletLocation = $bindable(),
		onManualChange
	}: {
		dialect: Dialect;
		transportMode: TransportSecurityMode;
		caPem: string;
		oracleWalletLocation: string;
		onManualChange: () => void;
	} = $props();

	const transportOptions: { value: TransportSecurityMode; label: string }[] = [
		{ value: 'VerifyIdentity', label: 'SSL with hostname verification' },
		{ value: 'VerifyCa', label: 'Verify CA' },
		{ value: 'EncryptOnly', label: 'SSL encrypt only (no cert check)' },
		{ value: 'Disabled', label: 'Disabled' }
	];

	function selectTransportMode(mode: TransportSecurityMode) {
		transportMode = mode;
		onManualChange();
	}
</script>

<details class="rounded-lg border border-slate-200 bg-slate-50/70 p-4 dark:border-slate-700 dark:bg-slate-800/40">
	<summary class="cursor-pointer text-sm font-medium text-slate-700 dark:text-slate-200">
		Advanced connection settings
	</summary>

	<div class="mt-4 space-y-4">
		<div>
			<span class="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300">Transport security</span>
			<div class="grid grid-cols-2 gap-2" role="group" aria-label="Transport security">
				{#each transportOptions as option (option.value)}
					<button
						type="button"
						onclick={() => selectTransportMode(option.value)}
						class="rounded-lg border px-3 py-2 text-sm font-medium {transportMode === option.value
							? 'border-slate-900 bg-slate-900 text-white dark:border-slate-100 dark:bg-slate-100 dark:text-slate-900'
							: 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800'}"
					>
						{option.label}
					</button>
				{/each}
			</div>
		</div>

		{#if transportMode === 'VerifyCa'}
			<div>
				<label class="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300" for="cf-ca">CA certificate (PEM)</label>
				<textarea
					id="cf-ca"
					bind:value={caPem}
					oninput={onManualChange}
					rows="4"
					class="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 font-mono text-xs text-slate-900 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
				></textarea>
			</div>
		{/if}

		{#if dialect === 'Oracle' && transportMode !== 'Disabled'}
			<div>
				<label class="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300" for="cf-wallet-advanced">Oracle wallet location</label>
				<input
					id="cf-wallet-advanced"
					type="text"
					bind:value={oracleWalletLocation}
					oninput={onManualChange}
					class="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
				/>
			</div>
		{/if}
	</div>
</details>
