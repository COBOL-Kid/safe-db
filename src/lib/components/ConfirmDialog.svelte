<script lang="ts">
	let {
		open = $bindable(false),
		title,
		message,
		confirmLabel = 'Delete',
		cancelLabel = 'Cancel',
		destructive = false,
		onConfirm,
		onCancel
	}: {
		open?: boolean;
		title: string;
		message: string;
		confirmLabel?: string;
		cancelLabel?: string;
		destructive?: boolean;
		onConfirm: () => void;
		onCancel: () => void;
	} = $props();

	let confirmButton = $state<HTMLButtonElement | null>(null);

	$effect(() => {
		if (open) {
			requestAnimationFrame(() => confirmButton?.focus());
		}
	});

	function handleKeydown(e: KeyboardEvent) {
		if (!open) return;
		if (e.key === 'Escape') {
			e.preventDefault();
			onCancel();
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
			role="alertdialog"
			aria-modal="true"
			aria-labelledby="confirm-dialog-title"
			aria-describedby="confirm-dialog-message"
			class="w-full max-w-sm rounded-xl border border-slate-200 bg-white p-6 shadow-xl dark:border-slate-700 dark:bg-slate-900"
		>
			<h2 id="confirm-dialog-title" class="text-base font-semibold text-slate-900 dark:text-slate-100">
				{title}
			</h2>
			<p id="confirm-dialog-message" class="mt-2 text-sm text-slate-600 dark:text-slate-400">
				{message}
			</p>
			<div class="mt-6 flex justify-end gap-2">
				<button
					type="button"
					onclick={onCancel}
					class="rounded-lg border border-slate-200 px-4 py-2 text-sm font-medium text-slate-600 transition-colors hover:bg-slate-50 dark:border-slate-700 dark:text-slate-400 dark:hover:bg-slate-800"
				>
					{cancelLabel}
				</button>
				<button
					bind:this={confirmButton}
					type="button"
					onclick={onConfirm}
					class="rounded-lg px-4 py-2 text-sm font-medium text-white transition-colors
						{destructive
						? 'bg-red-600 hover:bg-red-700 dark:bg-red-600 dark:hover:bg-red-500'
						: 'bg-slate-900 hover:bg-slate-700 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-300'}"
				>
					{confirmLabel}
				</button>
			</div>
		</div>
	</div>
{/if}
