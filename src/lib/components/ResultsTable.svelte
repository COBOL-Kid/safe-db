<script lang="ts">
	import type { QueryResult } from '$lib/ir';

	let { result }: { result: QueryResult } = $props();

	function formatCell(value: any): string {
		if (value === null || value === undefined) return '';
		if (typeof value === 'boolean') return value ? 'true' : 'false';
		return String(value);
	}
</script>

<div class="flex h-full flex-col overflow-hidden">
	<div class="flex items-center gap-3 border-b border-slate-200 bg-white px-4 py-2.5 dark:border-slate-700 dark:bg-slate-900">
		<span class="text-sm font-medium text-slate-700 dark:text-slate-200">
			{result.row_count} row{result.row_count !== 1 ? 's' : ''}
		</span>
		{#if result.truncated}
			<span class="flex items-center gap-1 rounded bg-amber-50 px-2 py-0.5 text-xs font-medium text-amber-600 dark:bg-amber-900/30 dark:text-amber-300">
				Truncated
			</span>
		{/if}
		{#if result.warnings.length > 0}
			<span class="flex items-center gap-1 rounded bg-orange-50 px-2 py-0.5 text-xs font-medium text-orange-600 dark:bg-orange-900/30 dark:text-orange-300">
				{result.warnings.length} warning{result.warnings.length !== 1 ? 's' : ''}
			</span>
		{/if}
	</div>

	{#if result.warnings.length > 0}
		<div class="border-b border-slate-200 bg-orange-50 px-4 py-2 dark:border-slate-700 dark:bg-orange-900/20">
			{#each result.warnings as warning (warning)}
				<p class="text-xs text-orange-700 dark:text-orange-300">⚠ {warning}</p>
			{/each}
		</div>
	{/if}

	<div class="flex-1 overflow-auto">
		{#if result.rows.length === 0}
			<div class="flex h-full items-center justify-center">
				<p class="text-sm text-slate-400 dark:text-slate-500">No rows returned.</p>
			</div>
		{:else}
			<table class="w-full border-collapse text-sm">
				<thead class="sticky top-0 bg-slate-50 dark:bg-slate-800/80">
					<tr>
						{#each result.columns as col (col)}
							<th class="border-b border-slate-200 px-3 py-2 text-left font-semibold text-slate-600 whitespace-nowrap dark:border-slate-700 dark:text-slate-300">
								{col}
							</th>
						{/each}
					</tr>
				</thead>
				<tbody>
					{#each result.rows as row, rowIdx (rowIdx)}
						<tr class="hover:bg-sky-50/40 dark:hover:bg-sky-900/20 {rowIdx % 2 === 1 ? 'bg-slate-50/40 dark:bg-slate-800/30' : ''}">
							{#each row as cell, colIdx (colIdx)}
								<td class="border-b border-slate-100 px-3 py-1.5 text-slate-700 whitespace-nowrap max-w-xs truncate dark:border-slate-800 dark:text-slate-200" title={formatCell(cell)}>
									{#if cell === null}
										<span class="text-slate-300 italic dark:text-slate-500">null</span>
									{:else}
										{formatCell(cell)}
									{/if}
								</td>
							{/each}
						</tr>
					{/each}
				</tbody>
			</table>
		{/if}
	</div>
</div>
