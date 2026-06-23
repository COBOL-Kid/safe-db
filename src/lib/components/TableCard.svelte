<script lang="ts">
	import type { CanvasTable } from '$lib/stores/query.svelte';
	import { query } from '$lib/stores/query.svelte';

	let {
		canvasTable,
		onStartJoin
	}: {
		canvasTable: CanvasTable;
		onStartJoin: (e: MouseEvent, alias: string, column: string) => void;
	} = $props();

	let table = $derived(canvasTable.tableInfo);
	let alias = $derived(canvasTable.alias);

	function removeTable(e: MouseEvent) {
		e.stopPropagation();
		query.removeTable(alias);
	}
</script>

<div
	class="absolute w-56 rounded-xl border border-slate-200 bg-white shadow-lg select-none"
	style="left: {canvasTable.x}px; top: {canvasTable.y}px;"
	data-alias={alias}
>
	<div class="flex items-center justify-between rounded-t-xl border-b border-slate-200 bg-slate-50 px-3 py-2.5 cursor-grab" data-drag-handle={alias}>
		<div class="flex items-center gap-2 min-w-0">
			<svg class="h-3.5 w-3.5 shrink-0 text-slate-400" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2" /><path d="M3 9h18M3 15h18M9 3v18M15 3v18" /></svg>
			<span class="truncate text-sm font-semibold text-slate-800">{table.name}</span>
		</div>
		<button
			type="button"
			onclick={removeTable}
			class="text-slate-300 transition-colors hover:text-red-500"
			aria-label="Remove table"
		>
			<svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
		</button>
	</div>

	<div class="max-h-64 overflow-y-auto py-1">
		{#each table.columns as col, i (col.name)}
			{@const selected = query.isColumnSelected(alias, col.name)}
			<div
				class="flex items-center gap-2 px-3 py-1.5 text-xs transition-colors hover:bg-slate-50 {selected ? 'bg-sky-50' : ''}"
				data-column-index={i}
				data-alias={alias}
				data-column={col.name}
				data-indexed={col.is_indexed}
			>
				<button
					type="button"
					class="flex flex-1 items-center gap-2 text-left min-w-0"
					onclick={() => query.toggleColumn(alias, col.name)}
				>
					<div class="flex h-3.5 w-3.5 shrink-0 items-center justify-center rounded border {selected ? 'border-sky-500 bg-sky-500' : 'border-slate-300'}">
						{#if selected}
							<svg class="h-2.5 w-2.5 text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5L20 7" /></svg>
						{/if}
					</div>
					<span class="truncate font-medium {selected ? 'text-sky-700' : 'text-slate-600'}">{col.name}</span>
					<span class="shrink-0 text-slate-300">{col.data_type}</span>
				</button>

				{#if col.is_indexed}
					<button
						type="button"
						class="join-handle flex h-4 w-4 shrink-0 cursor-crosshair items-center justify-center rounded-full bg-sky-100 text-sky-500 transition-colors hover:bg-sky-500 hover:text-white"
						title="Drag to another indexed column to join"
						data-join-source-alias={alias}
						data-join-source-column={col.name}
						onmousedown={(e) => onStartJoin(e, alias, col.name)}
					>
						<svg class="h-2.5 w-2.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="6" cy="12" r="2" /><circle cx="18" cy="12" r="2" /><path d="M8 12h8" /></svg>
					</button>
				{:else}
					<div class="h-4 w-4 shrink-0"></div>
				{/if}
			</div>
		{/each}
	</div>
</div>
