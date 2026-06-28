<script lang="ts">
	let {
		open = $bindable(false),
		title,
		message,
		value = $bindable(''),
		placeholder = '',
		confirmLabel = 'Save',
		cancelLabel = 'Cancel',
		onConfirm,
		onCancel
	}: {
		open?: boolean;
		title: string;
		message: string;
		value?: string;
		placeholder?: string;
		confirmLabel?: string;
		cancelLabel?: string;
		onConfirm: () => void;
		onCancel: () => void;
	} = $props();

	let inputEl = $state<HTMLInputElement | null>(null);

	$effect(() => {
		if (open) {
			requestAnimationFrame(() => inputEl?.focus());
		}
	});

	function handleKeydown(e: KeyboardEvent) {
		if (!open) return;
		if (e.key === 'Escape') {
			e.preventDefault();
			onCancel();
		}
		if (e.key === 'Enter') {
			e.preventDefault();
			onConfirm();
		}
	}

	function handleBackdropClick(e: MouseEvent) {
		if (e.target === e.currentTarget) {
			onCancel();
		}
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
			aria-labelledby="prompt-dialog-title"
			aria-describedby="prompt-dialog-message"
			class="w-full max-w-sm rounded-xl border border-slate-200 bg-white p-6 shadow-xl dark:border-slate-700 dark:bg-slate-900"
		>
			<h2 id="prompt-dialog-title" class="text-base font-semibold text-slate-900 dark:text-slate-100">
				{title}
			</h2>
			<p id="prompt-dialog-message" class="mt-2 text-sm text-slate-600 dark:text-slate-400">
				{message}
			</p>
			<input
				bind:this={inputEl}
				bind:value
				type="text"
				{placeholder}
				class="mt-4 w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none focus:border-slate-400 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
			/>
			<div class="mt-6 flex justify-end gap-2">
				<button
					type="button"
					onclick={onCancel}
					class="rounded-lg border border-slate-200 px-4 py-2 text-sm font-medium text-slate-600 transition-colors hover:bg-slate-50 dark:border-slate-700 dark:text-slate-400 dark:hover:bg-slate-800"
				>
					{cancelLabel}
				</button>
				<button
					type="button"
					onclick={onConfirm}
					class="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-slate-700 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-300"
				>
					{confirmLabel}
				</button>
			</div>
		</div>
	</div>
{/if}
