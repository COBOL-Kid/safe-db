<script lang="ts">
	import { goto } from '$app/navigation';
	import { connections } from '$lib/stores/connections.svelte';
	import { savedQueries } from '$lib/stores/saved-queries.svelte';
	import { query } from '$lib/stores/query.svelte';

	let {
		open = $bindable(false)
	}: { open: boolean } = $props();

	let search = $state('');
	let selectedIndex = $state(0);

	type Command = {
		id: string;
		label: string;
		hint: string;
		icon: string;
		action: () => void;
	};

	const iconPaths: Record<string, string> = {
		nav: 'M5 12h14M12 5l7 7-7 7',
		conn: 'M12 5v14M5 12h14',
		query: 'M4 6h16M4 12h16M4 18h10',
		run: 'M5 3l14 9-14 9V3z',
		save: 'M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2zM17 21v-8H7v8M7 3v5h8',
		history: 'M12 8v4l3 2M12 22a10 10 0 1 1 0-20 10 10 0 0 1 0 20z',
		home: 'M3 12l9-9 9 9M5 10v10a1 1 0 0 0 1 1h3v-6h6v6h3a1 1 0 0 0 1-1V10',
		clear: 'M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6'
	};

	const commands = $derived.by<Command[]>(() => {
		const cmds: Command[] = [
			{
				id: 'nav-home',
				label: 'Go to Home',
				hint: 'Home page',
				icon: 'home',
				action: () => goto('/')
			},
			{
				id: 'nav-connections',
				label: 'Go to Connections',
				hint: 'Manage connections',
				icon: 'conn',
				action: () => goto('/connections')
			},
			{
				id: 'nav-builder',
				label: 'Go to Query Builder',
				hint: 'Build queries',
				icon: 'query',
				action: () => goto('/builder')
			},
			{
				id: 'nav-history',
				label: 'Go to History',
				hint: 'Recent queries',
				icon: 'history',
				action: () => goto('/history')
			}
		];

		if (connections.activeId && query.tables.length > 0) {
			cmds.push({
				id: 'run-query',
				label: 'Run Query',
				hint: 'Execute current query',
				icon: 'run',
				action: () => {
					if (connections.activeId) query.run(connections.activeId);
					goto('/builder');
				}
			});
			cmds.push({
				id: 'clear-canvas',
				label: 'Clear Canvas',
				hint: 'Remove all tables',
				icon: 'clear',
				action: () => query.clear()
			});
		}

		for (const conn of connections.connections) {
			cmds.push({
				id: `conn-${conn.id}`,
				label: `Explore: ${conn.name}`,
				hint: `${conn.dialect} · ${conn.database}`,
				icon: 'conn',
				action: () => {
					connections.setActive(conn.id);
					goto('/builder');
				}
			});
		}

		return cmds;
	});

	const filtered = $derived.by(() => {
		const q = search.trim().toLowerCase();
		if (!q) return commands;
		return commands.filter((c) => c.label.toLowerCase().includes(q) || c.hint.toLowerCase().includes(q));
	});

	$effect(() => {
		if (open) {
			search = '';
			selectedIndex = 0;
		}
	});

	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') {
			open = false;
		} else if (e.key === 'ArrowDown') {
			e.preventDefault();
			selectedIndex = Math.min(selectedIndex + 1, filtered.length - 1);
		} else if (e.key === 'ArrowUp') {
			e.preventDefault();
			selectedIndex = Math.max(selectedIndex - 1, 0);
		} else if (e.key === 'Enter') {
			e.preventDefault();
			const cmd = filtered[selectedIndex];
			if (cmd) {
				cmd.action();
				open = false;
			}
		}
	}

	function executeCommand(cmd: Command) {
		cmd.action();
		open = false;
	}
</script>

<svelte:window onkeydown={(e) => {
	if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
		e.preventDefault();
		open = !open;
	}
}} />

{#if open}
	<div
		class="fixed inset-0 z-50 flex items-start justify-center bg-black/20 backdrop-blur-sm pt-24"
		onclick={() => (open = false)}
		onkeydown={handleKeydown}
		role="presentation"
	>
		<!-- svelte-ignore a11y_no_noninteractive_element_interactions, a11y_no_static_element_interactions -->
		<div
			class="w-full max-w-lg rounded-xl border border-slate-200 bg-white shadow-2xl overflow-hidden dark:border-slate-700 dark:bg-slate-900"
			onclick={(e) => e.stopPropagation()}
			onkeydown={handleKeydown}
			tabindex="-1"
			role="dialog"
			aria-label="Command palette"
		>
			<div class="border-b border-slate-200 p-3 dark:border-slate-700">
				<div class="relative">
					<svg class="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400 dark:text-slate-500" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8" /><path d="m21 21-4.35-4.35" /></svg>
					<input
						type="text"
						placeholder="Type a command…"
						bind:value={search}
						class="w-full rounded-lg bg-slate-50 py-2.5 pl-9 pr-3 text-sm text-slate-900 outline-none placeholder:text-slate-400 dark:bg-slate-800 dark:text-slate-100 dark:placeholder:text-slate-500"
						aria-label="Command search"
					/>
				</div>
			</div>

			<div class="max-h-80 overflow-y-auto p-2">
				{#if filtered.length === 0}
					<div class="px-3 py-8 text-center text-sm text-slate-400 dark:text-slate-500">No commands found</div>
				{:else}
					{#each filtered as cmd, i (cmd.id)}
						<button
							type="button"
							onclick={() => executeCommand(cmd)}
							onmouseenter={() => (selectedIndex = i)}
							class="flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-left transition-colors
								{i === selectedIndex ? 'bg-slate-100 dark:bg-slate-800' : 'hover:bg-slate-50 dark:hover:bg-slate-800/60'}"
						>
							<div class="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400">
								<svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d={iconPaths[cmd.icon]} /></svg>
							</div>
							<div class="flex-1 min-w-0">
								<p class="text-sm font-medium text-slate-800 dark:text-slate-100">{cmd.label}</p>
								<p class="text-xs text-slate-400 dark:text-slate-500">{cmd.hint}</p>
							</div>
						</button>
					{/each}
				{/if}
			</div>

			<div class="border-t border-slate-200 px-3 py-2 text-xs text-slate-400 dark:border-slate-700 dark:text-slate-500">
				<span class="flex items-center gap-3">
					<span><kbd class="rounded border border-slate-200 px-1 dark:border-slate-700">↑↓</kbd> navigate</span>
					<span><kbd class="rounded border border-slate-200 px-1 dark:border-slate-700">↵</kbd> select</span>
					<span><kbd class="rounded border border-slate-200 px-1 dark:border-slate-700">esc</kbd> close</span>
				</span>
			</div>
		</div>
	</div>
{/if}
