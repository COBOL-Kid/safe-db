<script lang="ts">
	import type { CanvasTable } from '$lib/stores/query.svelte';
	import { query } from '$lib/stores/query.svelte';
	import { CANVAS_CARD_HEIGHT, CANVAS_CARD_WIDTH, CANVAS_HEADER_HEIGHT } from '$lib/canvas-geometry';
	import {
		opsForColumn,
		literalKindForColumn,
		valueKindForOp,
		opLabel,
		type FilterSpec,
		type FilterLiteral
	} from '$lib/ir';

	let {
		canvasTable,
		onStartJoin,
		onStartResize,
		highlightJoinTargets = null
	}: {
		canvasTable: CanvasTable;
		onStartJoin: (e: MouseEvent, alias: string, column: string) => void;
		onStartResize: (e: MouseEvent, alias: string) => void;
		highlightJoinTargets?: { sourceAlias: string; sourceColumn: string } | null;
	} = $props();

	let table = $derived(canvasTable.tableInfo);
	let alias = $derived(canvasTable.alias);
	let cardWidth = $derived(canvasTable.width ?? CANVAS_CARD_WIDTH);
	let cardHeight = $derived(canvasTable.height ?? CANVAS_CARD_HEIGHT);
	let bodyHeight = $derived(Math.max(64, cardHeight - CANVAS_HEADER_HEIGHT));
	let menuColumn = $state<string | null>(null);

	function isJoinTarget(colName: string, isIndexed: boolean): boolean {
		if (!highlightJoinTargets || !isIndexed) return false;
		if (
			highlightJoinTargets.sourceAlias === alias &&
			highlightJoinTargets.sourceColumn === colName
		) {
			return false;
		}
		return true;
	}

	function removeTable(e: MouseEvent) {
		e.stopPropagation();
		query.removeTable(alias);
	}

	function toggleMenu(colName: string, e: MouseEvent) {
		e.stopPropagation();
		menuColumn = menuColumn === colName ? null : colName;
	}

	function closeMenu() {
		menuColumn = null;
	}

	function quickFilter(colName: string, op: string) {
		const col = table.columns.find((c) => c.name === colName);
		if (!col) return;
		const kind = literalKindForColumn(col.data_type);
		const vk = valueKindForOp(op as any);
		let value = null;
		if (vk === 'Single') {
			value = { Single: { kind, text: '' } };
		} else if (vk === 'List') {
			value = { List: [{ kind, text: '' }] };
		} else if (vk === 'Pair') {
			const pair: [FilterLiteral, FilterLiteral] = [
				{ kind, text: '' },
				{ kind, text: '' }
			];
			value = { Pair: pair };
		}
		const spec: FilterSpec = { table_alias: alias, column: colName, op: op as any, value };
		query.addFilter(spec);
		closeMenu();
	}

	function getMenuOps(colName: string) {
		const col = table.columns.find((c) => c.name === colName);
		if (!col) return [];
		return opsForColumn(col.data_type);
	}
</script>

<svelte:window onclick={closeMenu} />

<div
	class="absolute flex flex-col rounded-xl border border-slate-200 bg-white shadow-lg select-none dark:border-slate-700 dark:bg-slate-900"
	style="left: {canvasTable.x}px; top: {canvasTable.y}px; width: {cardWidth}px; height: {cardHeight}px;"
	data-alias={alias}
>
	<div class="flex shrink-0 items-center justify-between rounded-t-xl border-b border-slate-200 bg-slate-50 px-3 py-2.5 cursor-grab dark:border-slate-700 dark:bg-slate-800/60" data-drag-handle={alias}>
		<div class="flex items-center gap-2 min-w-0">
			<svg class="h-3.5 w-3.5 shrink-0 text-slate-400" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2" /><path d="M3 9h18M3 15h18M9 3v18M15 3v18" /></svg>
			<span class="truncate text-sm font-semibold text-slate-800 dark:text-slate-100">{table.name}</span>
		</div>
		<button
			type="button"
			onclick={removeTable}
			class="text-slate-300 transition-colors hover:text-red-500 dark:text-slate-500 dark:hover:text-red-400"
			aria-label="Remove table"
		>
			<svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
		</button>
	</div>

	<div class="min-h-0 flex-1 space-y-0.5 overflow-y-auto px-1 py-1" style="height: {bodyHeight}px;">
		{#each table.columns as col, i (col.name)}
			{@const selected = query.isColumnSelected(alias, col.name)}
			{@const joinTarget = isJoinTarget(col.name, col.is_indexed)}
			<div
				class="relative flex items-center gap-2 rounded-md px-2 py-1.5 text-xs transition-[background-color,box-shadow,color] hover:bg-slate-50 dark:hover:bg-slate-800/60 {selected ? 'bg-sky-50 dark:bg-sky-900/30' : ''} {joinTarget ? 'bg-sky-50/80 ring-1 ring-inset ring-sky-300/70 dark:bg-sky-900/30 dark:ring-sky-500/45' : ''}"
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
					<div class="flex h-3.5 w-3.5 shrink-0 items-center justify-center rounded border {selected ? 'border-sky-500 bg-sky-500' : 'border-slate-300 dark:border-slate-600'}">
						{#if selected}
							<svg class="h-2.5 w-2.5 text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5L20 7" /></svg>
						{/if}
					</div>
					<span class="truncate font-medium {selected ? 'text-sky-700 dark:text-sky-300' : 'text-slate-600 dark:text-slate-300'}">{col.name}</span>
					<span class="shrink-0 text-slate-300 dark:text-slate-500">{col.data_type}</span>
				</button>

				<button
					type="button"
					class="flex h-4 w-4 shrink-0 items-center justify-center rounded text-slate-300 transition-colors hover:bg-slate-200 hover:text-slate-600 dark:text-slate-500 dark:hover:bg-slate-700 dark:hover:text-slate-200"
					title="Filter options"
					onclick={(e) => toggleMenu(col.name, e)}
				>
					<svg class="h-3 w-3" viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="2" /><circle cx="12" cy="12" r="2" /><circle cx="19" cy="12" r="2" /></svg>
				</button>

				{#if col.is_indexed}
					<button
						type="button"
						class="join-handle flex h-4 w-4 shrink-0 cursor-crosshair items-center justify-center rounded-full bg-sky-100 text-sky-500 transition-colors hover:bg-sky-500 hover:text-white dark:bg-sky-900/40 dark:text-sky-400 dark:hover:bg-sky-500 dark:hover:text-white"
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

				{#if menuColumn === col.name}
					<!-- svelte-ignore a11y_interactive_supports_focus -->
					<!-- svelte-ignore a11y_click_events_have_key_events -->
					<div
						class="absolute right-0 top-full z-50 mt-0.5 min-w-44 rounded-lg border border-slate-200 bg-white py-1 shadow-lg dark:border-slate-700 dark:bg-slate-900"
						role="menu"
						onclick={(e) => e.stopPropagation()}
					>
						<div class="px-3 py-1 text-[10px] font-semibold uppercase tracking-wide text-slate-400">Filter where</div>
						{#each getMenuOps(col.name) as op (op)}
							<button
								type="button"
								class="block w-full px-3 py-1 text-left text-xs text-slate-600 transition-colors hover:bg-sky-50 hover:text-sky-700 dark:text-slate-300 dark:hover:bg-sky-900/40 dark:hover:text-sky-300"
								onclick={() => quickFilter(col.name, op)}
							>
								{col.name} {opLabel(op)}
							</button>
						{/each}
					</div>
				{/if}
			</div>
		{/each}
	</div>

	<button
		type="button"
		class="absolute bottom-1 right-1 h-4 w-4 cursor-nwse-resize rounded-sm text-black transition-colors hover:bg-slate-100 hover:text-black dark:text-white dark:hover:bg-slate-800 dark:hover:text-white"
		aria-label="Resize table"
		onmousedown={(e) => onStartResize(e, alias)}
	>
		<svg class="h-4 w-4" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round">
			<path d="M10 13h3v-3M6 13h.01M9 10h.01M12 7h.01" />
		</svg>
	</button>
</div>
