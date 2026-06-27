<script lang="ts">
	import { settings } from '$lib/stores/settings.svelte';

	let {
		open = $bindable(false)
	}: {
		open?: boolean;
	} = $props();

	let newSchema = $state('');
	let thresholdInput = $state('100000');
	let saveError = $state<string | null>(null);

	$effect(() => {
		if (open) {
			thresholdInput = String(settings.settings.explain_cost_threshold);
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
		const n = parseFloat(thresholdInput);
		if (isNaN(n) || n < 1 || n > 10_000_000) {
			saveError = 'Threshold must be between 1 and 10,000,000';
			return;
		}
		settings.settings.explain_cost_threshold = n;
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
					<label class="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300" for="cost-threshold">
						EXPLAIN cost threshold
					</label>
					<div class="flex gap-2">
						<input
							id="cost-threshold"
							type="number"
							min="1"
							max="10000000"
							bind:value={thresholdInput}
							class="flex-1 rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800"
						/>
						<button
							type="button"
							onclick={saveThreshold}
							class="rounded-lg bg-slate-900 px-3 py-2 text-sm font-medium text-white hover:bg-slate-700 dark:bg-slate-100 dark:text-slate-900"
						>
							Save
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
