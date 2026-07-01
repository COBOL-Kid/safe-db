<script lang="ts">
	import TableCard from './TableCard.svelte';
	import { query } from '$lib/stores/query.svelte';
	import type { JoinSpec } from '$lib/ir';
	import {
		CANVAS_CARD_HEIGHT,
		CANVAS_CARD_WIDTH,
		columnY,
		joinEdgePath as buildJoinEdgePath,
		tableLeftX,
		tableRightX
	} from '$lib/canvas-geometry';

	let canvasEl: HTMLDivElement;

	let dragTable: { alias: string; offsetX: number; offsetY: number } | null = $state(null);
	let resizeTable: {
		alias: string;
		startX: number;
		startY: number;
		startWidth: number;
		startHeight: number;
	} | null = $state(null);
	let dragJoin: {
		sourceAlias: string;
		sourceColumn: string;
		mouseX: number;
		mouseY: number;
	} | null = $state(null);
	let hoveredJoinIndex = $state<number | null>(null);
	let focusedJoinIndex = $state<number | null>(null);

	function getColumnY(alias: string, columnName: string): number {
		const ct = query.tables.find((t) => t.alias === alias);
		if (!ct) return 0;
		return columnY(ct, columnName);
	}

	function joinEdgePath(join: JoinSpec): string {
		const left = query.tables.find((t) => t.alias === join.left_alias);
		const right = query.tables.find((t) => t.alias === join.right_alias);
		if (!left || !right) return '';
		return buildJoinEdgePath(left, join.left_column, right, join.right_column);
	}

	function getTableRightX(alias: string): number {
		const t = query.tables.find((t) => t.alias === alias);
		return t ? tableRightX(t) : 0;
	}

	function getTableLeftX(alias: string): number {
		const t = query.tables.find((t) => t.alias === alias);
		return t ? tableLeftX(t) : 0;
	}

	function getCanvasCoords(e: MouseEvent): { x: number; y: number } {
		const rect = canvasEl.getBoundingClientRect();
		return {
			x: e.clientX - rect.left + canvasEl.scrollLeft,
			y: e.clientY - rect.top + canvasEl.scrollTop
		};
	}

	function handleMouseDown(e: MouseEvent) {
		const target = e.target as HTMLElement;
		const handle = target.closest('[data-drag-handle]') as HTMLElement | null;
		if (handle) {
			const alias = handle.dataset.dragHandle!;
			const ct = query.tables.find((t) => t.alias === alias);
			if (ct) {
				const coords = getCanvasCoords(e);
				dragTable = {
					alias,
					offsetX: coords.x - ct.x,
					offsetY: coords.y - ct.y
				};
				e.preventDefault();
			}
		}
	}

	function handleStartJoin(e: MouseEvent, alias: string, column: string) {
		const coords = getCanvasCoords(e);
		dragJoin = {
			sourceAlias: alias,
			sourceColumn: column,
			mouseX: coords.x,
			mouseY: coords.y
		};
		e.preventDefault();
		e.stopPropagation();
	}

	function handleStartResize(e: MouseEvent, alias: string) {
		const ct = query.tables.find((t) => t.alias === alias);
		if (!ct) return;
		const coords = getCanvasCoords(e);
		resizeTable = {
			alias,
			startX: coords.x,
			startY: coords.y,
			startWidth: ct.width ?? CANVAS_CARD_WIDTH,
			startHeight: ct.height ?? CANVAS_CARD_HEIGHT
		};
		e.preventDefault();
		e.stopPropagation();
	}

	function handleMouseMove(e: MouseEvent) {
		if (dragTable) {
			const coords = getCanvasCoords(e);
			query.moveTable(dragTable.alias, coords.x - dragTable.offsetX, coords.y - dragTable.offsetY);
		} else if (resizeTable) {
			const coords = getCanvasCoords(e);
			query.resizeTable(
				resizeTable.alias,
				resizeTable.startWidth + coords.x - resizeTable.startX,
				resizeTable.startHeight + coords.y - resizeTable.startY
			);
		} else if (dragJoin) {
			const coords = getCanvasCoords(e);
			dragJoin = { ...dragJoin, mouseX: coords.x, mouseY: coords.y };
		}
	}

	function handleMouseUp(e: MouseEvent) {
		if (dragJoin) {
			const target = document.elementFromPoint(e.clientX, e.clientY) as HTMLElement | null;
			const colRow = target?.closest('[data-indexed="true"]') as HTMLElement | null;
			if (colRow) {
				const targetAlias = colRow.dataset.alias!;
				const targetColumn = colRow.dataset.column!;
				if (targetAlias !== dragJoin.sourceAlias || targetColumn !== dragJoin.sourceColumn) {
					query.addJoin({
						left_alias: dragJoin.sourceAlias,
						left_column: dragJoin.sourceColumn,
						right_alias: targetAlias,
						right_column: targetColumn
					});
				}
			}
		}
		dragTable = null;
		resizeTable = null;
		dragJoin = null;
	}

	function handleJoinClick(index: number) {
		query.removeJoin(index);
	}

	function handleJoinKey(e: KeyboardEvent, index: number) {
		if (e.key === 'Enter' || e.key === ' ' || e.key === 'Delete' || e.key === 'Backspace') {
			e.preventDefault();
			query.removeJoin(index);
		}
	}
</script>

<svelte:window onmousemove={handleMouseMove} onmouseup={handleMouseUp} />

<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
<div
	bind:this={canvasEl}
	class="relative h-full w-full overflow-auto bg-slate-50 dark:bg-slate-950"
	role="application"
	aria-label="Query canvas"
	onmousedown={handleMouseDown}
>
	<div class="relative" style="min-width: 2400px; min-height: 1800px;">
		<svg class="pointer-events-none absolute inset-0" style="width: 100%; height: 100%;">
			{#each query.joins as join, i (i)}
				{@const isHovered = hoveredJoinIndex === i}
				<!-- Wide invisible hit area for click + keyboard focus -->
				<path
					d={joinEdgePath(join)}
					stroke="transparent"
					stroke-width="12"
					fill="none"
					class="pointer-events-auto cursor-pointer focus:outline-none"
					role="button"
					tabindex="0"
					aria-label={`Remove join: ${join.left_alias}.${join.left_column} to ${join.right_alias}.${join.right_column}`}
					onclick={() => handleJoinClick(i)}
					onkeydown={(e) => handleJoinKey(e, i)}
					onfocus={() => {
						focusedJoinIndex = i;
						hoveredJoinIndex = i;
					}}
					onblur={() => {
						if (focusedJoinIndex === i) focusedJoinIndex = null;
						if (hoveredJoinIndex === i) hoveredJoinIndex = null;
					}}
					onmouseenter={() => (hoveredJoinIndex = i)}
					onmouseleave={() => {
						if (hoveredJoinIndex === i) hoveredJoinIndex = null;
					}}
				/>
				<path
					d={joinEdgePath(join)}
					stroke={isHovered ? '#dc2626' : '#0ea5e9'}
					stroke-width={isHovered ? '3' : '2'}
					fill="none"
					stroke-dasharray="0"
					pointer-events="none"
				/>
				<path
					d={joinEdgePath(join)}
					stroke="#0ea5e9"
					stroke-width="6"
					fill="none"
					pointer-events="none"
					class:opacity-0={focusedJoinIndex !== i}
				/>
				<circle
					cx={getTableRightX(join.left_alias)}
					cy={getColumnY(join.left_alias, join.left_column)}
					r="4"
					fill={isHovered ? '#dc2626' : '#0ea5e9'}
					pointer-events="none"
				/>
				<circle
					cx={getTableLeftX(join.right_alias)}
					cy={getColumnY(join.right_alias, join.right_column)}
					r="4"
					fill={isHovered ? '#dc2626' : '#0ea5e9'}
					pointer-events="none"
				/>
			{/each}
			{#if dragJoin}
				{@const sourceX = getTableRightX(dragJoin.sourceAlias)}
				{@const sourceY = getColumnY(dragJoin.sourceAlias, dragJoin.sourceColumn)}
				<path
					d={`M ${sourceX} ${sourceY} L ${dragJoin.mouseX} ${dragJoin.mouseY}`}
					stroke="#0ea5e9"
					stroke-width="2"
					stroke-dasharray="5,3"
					fill="none"
					opacity="0.6"
					pointer-events="none"
				/>
			{/if}
		</svg>

		{#each query.tables as canvasTable (canvasTable.alias)}
			<TableCard
				{canvasTable}
				onStartJoin={handleStartJoin}
				onStartResize={handleStartResize}
				highlightJoinTargets={dragJoin
					? { sourceAlias: dragJoin.sourceAlias, sourceColumn: dragJoin.sourceColumn }
					: null}
			/>
		{/each}
	</div>

	{#if query.tables.length > 0}
		<div class="pointer-events-none absolute bottom-3 left-3 rounded-lg bg-white/90 px-3 py-1.5 text-xs text-slate-500 shadow-sm backdrop-blur dark:bg-slate-900/90 dark:text-slate-400">
			{query.tables.length} table{query.tables.length !== 1 ? 's' : ''}
			{#if query.joins.length > 0}
				· {query.joins.length} join{query.joins.length !== 1 ? 's' : ''}
			{/if}
			{#if query.selectedColumns.size > 0}
				· {query.selectedColumns.size} column{query.selectedColumns.size !== 1 ? 's' : ''}
			{/if}
			{#if query.filterCount > 0}
				· {query.filterCount} filter{query.filterCount !== 1 ? 's' : ''}
			{/if}
		</div>
	{/if}
</div>
