<script lang="ts">
	import './layout.css';
	import favicon from '$lib/assets/favicon.svg';
	import { page } from '$app/state';
	import { browser } from '$app/environment';
	import { settings } from '$lib/stores/settings.svelte';
	import { connections } from '$lib/stores/connections.svelte';
	import { savedQueries } from '$lib/stores/saved-queries.svelte';
	import { history } from '$lib/stores/history.svelte';
	import CommandPalette from '$lib/components/CommandPalette.svelte';
	import SettingsPanel from '$lib/components/SettingsPanel.svelte';

	let { children } = $props();

	let paletteOpen = $state(false);
	let settingsOpen = $state(false);
	let isMac = $derived(browser && /Mac/i.test(navigator.userAgent));

	const navItems = [
		{ href: '/', label: 'Home', icon: 'home' },
		{ href: '/connections', label: 'Connections', icon: 'plug' },
		{ href: '/builder', label: 'Query Builder', icon: 'builder' },
		{ href: '/history', label: 'History', icon: 'clock' }
	];

	function isActive(href: string): boolean {
		if (href === '/') return page.url.pathname === '/';
		return page.url.pathname.startsWith(href);
	}

	const iconPaths: Record<string, string> = {
		home: 'M3 12l9-9 9 9M5 10v10a1 1 0 0 0 1 1h3v-6h6v6h3a1 1 0 0 0 1-1V10',
		plug: 'M9 7V3M15 7V3M7 7h10v4a5 5 0 0 1-10 0V7zM12 16v5',
		builder: 'M4 6h16M4 12h16M4 18h10',
		clock: 'M12 7v5l3 2M12 22a10 10 0 1 1 0-20 10 10 0 0 1 0 20z',
		sun: 'M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z',
		moon: 'M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z',
		command: 'M6 9a3 3 0 1 1 0-6 3 3 0 0 1 0 6zM6 21a3 3 0 1 1 0-6 3 3 0 0 1 0 6zM18 9a3 3 0 1 1 0-6 3 3 0 0 1 0 6zM18 21a3 3 0 1 1 0-6 3 3 0 0 1 0 6zM9 6h6M9 18h6M6 9v6M18 9v6',
		settings: 'M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zM19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9c.26.604.852.997 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z'
	};

	$effect(() => {
		if (browser) {
			settings.load();
			connections.load();
			savedQueries.load();
			history.load();
		}
	});
</script>

<svelte:head><link rel="icon" href={favicon} /></svelte:head>

<CommandPalette bind:open={paletteOpen} />
<SettingsPanel bind:open={settingsOpen} />

<div class="flex h-screen w-screen overflow-hidden bg-slate-50 text-slate-900 dark:bg-slate-950 dark:text-slate-100">
	<aside class="flex w-56 flex-col border-r border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
		<div class="tauri-drag flex items-center gap-2.5 border-b border-slate-200 px-5 py-5 dark:border-slate-800">
			<div class="tauri-no-drag flex h-8 w-8 items-center justify-center rounded-lg bg-slate-900 text-sm font-bold text-white dark:bg-slate-100 dark:text-slate-900">
				sd
			</div>
			<span class="tauri-no-drag text-sm font-semibold tracking-tight">safe-db</span>
		</div>

		<nav class="flex flex-1 flex-col gap-1 p-3">
			{#each navItems as item (item.href)}
				<a
					href={item.href}
					class="tauri-no-drag flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors
					{isActive(item.href)
						? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900'
						: 'text-slate-600 hover:bg-slate-100 hover:text-slate-900 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-slate-100'}"
				>
					<svg
						class="h-4.5 w-4.5 shrink-0"
						viewBox="0 0 24 24"
						fill="none"
						stroke="currentColor"
						stroke-width="1.8"
						stroke-linecap="round"
						stroke-linejoin="round"
						aria-hidden="true"
					>
						<path d={iconPaths[item.icon]} />
					</svg>
					{item.label}
				</a>
			{/each}
		</nav>

		<div class="space-y-2 border-t border-slate-200 p-3 dark:border-slate-800">
			<button
				type="button"
				onclick={() => (paletteOpen = true)}
				class="tauri-no-drag flex w-full items-center gap-2 rounded-lg border border-slate-200 px-3 py-2 text-xs text-slate-400 transition-colors hover:bg-slate-50 dark:border-slate-700 dark:hover:bg-slate-800"
			>
				<svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d={iconPaths.command} /></svg>
				<span>Command</span>
				<kbd class="ml-auto rounded border border-slate-200 px-1 text-[10px] dark:border-slate-700">⌘K</kbd>
			</button>

			<div class="flex items-center justify-between">
				<div class="rounded-lg bg-slate-50 px-3 py-2 dark:bg-slate-800">
					<p class="text-xs font-medium text-slate-500 dark:text-slate-400">Safe Read Mode</p>
					<p class="mt-0.5 text-xs text-slate-400 dark:text-slate-500">No-lock · Indexed joins</p>
				</div>
				<div class="flex items-center gap-1">
					<button
						type="button"
						onclick={() => (settingsOpen = true)}
						class="tauri-no-drag flex h-8 w-8 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700 dark:hover:bg-slate-800 dark:hover:text-slate-200"
						aria-label="Settings"
					>
						<svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
							<path d={iconPaths.settings} />
						</svg>
					</button>
					<button
						type="button"
						onclick={() => settings.toggleTheme()}
						class="tauri-no-drag flex h-8 w-8 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700 dark:hover:bg-slate-800 dark:hover:text-slate-200"
						aria-label="Toggle theme"
					>
						<svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
							{#if settings.isDark}
								<path d={iconPaths.sun} />
							{:else}
								<path d={iconPaths.moon} />
							{/if}
						</svg>
					</button>
				</div>
			</div>
		</div>
	</aside>

	<main class="flex flex-1 flex-col overflow-hidden">
		{#if isMac}
			<div class="tauri-drag h-7 shrink-0" aria-hidden="true"></div>
		{/if}
		<div class="flex flex-1 flex-col overflow-hidden">
			{@render children()}
		</div>
	</main>
</div>
