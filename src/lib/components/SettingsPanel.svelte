<script lang="ts">
	import { settings } from '$lib/stores/settings.svelte';
	import { DIALECTS, type Dialect } from '$lib/ir';

	let {
		open = $bindable(false)
	}: {
		open?: boolean;
	} = $props();

	let newSchema = $state('');
	let thresholdInputs = $state<Record<Dialect, string>>({
		Postgres: '100000',
		MySql: '100000',
		Mssql: '100000',
		Oracle: '100000'
	});
	let saveError = $state<string | null>(null);

	$effect(() => {
		if (open) {
			thresholdInputs = {
				Postgres: String(settings.settings.explain_cost_thresholds?.Postgres ?? settings.settings.explain_cost_threshold),
				MySql: String(settings.settings.explain_cost_thresholds?.MySql ?? settings.settings.explain_cost_threshold),
				Mssql: String(settings.settings.explain_cost_thresholds?.Mssql ?? settings.settings.explain_cost_threshold),
				Oracle: String(settings.settings.explain_cost_thresholds?.Oracle ?? settings.settings.explain_cost_threshold)
			};
			newSchema = '';
			saveError = null;
		}
	});

	function handleBackdropClick(e: MouseEvent) {
		if (e.target === e.currentTarget) {
			open = false;
		}
	}

	function handleKeydown(e: KeyboardEvent) {
		if (!open) return;
		if (e.key === 'Escape') {
			e.preventDefault();
			open = false;
		}
	}

	async function addSchema() {
		const schema = newSchema.trim().toLowerCase();
		if (!schema) return;
		await settings.addBlockedSchema(schema);
		newSchema = '';
	}

	async function removeSchema(schema: string) {
		await settings.removeBlockedSchema(schema);
	}

	async function saveThreshold() {
		saveError = null;
		const thresholds = {} as Record<Dialect, number>;
		for (const dialect of DIALECTS) {
			const n = parseFloat(thresholdInputs[dialect.value]);
			if (isNaN(n) || n < 1 || n > 10_000_000) {
				saveError = `${dialect.label} threshold must be between 1 and 10,000,000`;
				return;
			}
			thresholds[dialect.value] = n;
		}
		settings.settings.explain_cost_thresholds = thresholds;
		settings.settings.explain_cost_threshold = thresholds.Postgres;
		await settings.save();
	}
</script>

<svelte:window onkeydown={handleKeydown} />

{#if open}
	<div
		class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
		role="presentation"
		onclick={handleBackdropClick}
	>
		<div
			role="dialog"
			aria-modal="true"
			aria-labelledby="settings-panel-title"
			class="max-h-[90vh] w-full max-w-md overflow-y-auto rounded-xl border border-slate-200 bg-white p-6 shadow-xl dark:border-slate-700 dark:bg-slate-900"
		>
			<h2 id="settings-panel-title" class="text-base font-semibold text-slate-900 dark:text-slate-100">
				Settings
			</h2>

			<div class="mt-5 space-y-5">
				<div>
					<p class="mb-1.5 text-sm font-medium text-slate-700 dark:text-slate-300">
						EXPLAIN cost thresholds
					</p>
					<div class="space-y-2">
						{#each DIALECTS as dialect (dialect.value)}
							<label class="grid grid-cols-[7rem_1fr] items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
								<span>{dialect.label}</span>
								<input
									type="number"
									min="1"
									max="10000000"
									bind:value={thresholdInputs[dialect.value]}
									aria-label={`${dialect.label} EXPLAIN cost threshold`}
									class="rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-900 outline-none focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
								/>
							</label>
						{/each}
						<button
							type="button"
							onclick={saveThreshold}
							class="rounded-lg bg-slate-900 px-3 py-2 text-sm font-medium text-white hover:bg-slate-700 dark:bg-slate-100 dark:text-slate-900"
						>
							Save thresholds
						</button>
					</div>
					{#if saveError}
						<p class="mt-1 text-xs text-red-600 dark:text-red-400">{saveError}</p>
					{/if}
					<p class="mt-1 text-xs text-slate-400">Queries above this estimated cost require confirmation.</p>
				</div>

				<div>
					<p class="mb-1.5 text-sm font-medium text-slate-700 dark:text-slate-300">Blocked schemas</p>
					<div class="flex gap-2">
						<input
							type="text"
							placeholder="schema name"
							bind:value={newSchema}
							class="flex-1 rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800"
						/>
						<button
							type="button"
							onclick={addSchema}
							class="rounded-lg border border-slate-200 px-3 py-2 text-sm font-medium text-slate-600 hover:bg-slate-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
						>
							Add
						</button>
					</div>
					{#if settings.settings.blocked_schemas.length > 0}
						<ul class="mt-2 space-y-1">
							{#each settings.settings.blocked_schemas as schema (schema)}
								<li class="flex items-center justify-between rounded-lg bg-slate-50 px-3 py-1.5 text-sm dark:bg-slate-800">
									<span class="font-mono text-slate-700 dark:text-slate-200">{schema}</span>
									<button
										type="button"
										onclick={() => removeSchema(schema)}
										class="text-slate-400 hover:text-red-500"
										aria-label={`Remove blocked schema ${schema}`}
									>
										Remove
									</button>
								</li>
							{/each}
						</ul>
					{:else}
						<p class="mt-2 text-xs text-slate-400">No custom blocked schemas.</p>
					{/if}
				</div>
			</div>

			<div class="mt-6 flex justify-end">
				<button
					type="button"
					onclick={() => (open = false)}
					class="rounded-lg border border-slate-200 px-4 py-2 text-sm font-medium text-slate-600 hover:bg-slate-50 dark:border-slate-700 dark:text-slate-400 dark:hover:bg-slate-800"
				>
					Close
				</button>
			</div>
		</div>
	</div>
{/if}
