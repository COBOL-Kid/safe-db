<script lang="ts">
	import './layout.css';
	import favicon from '$lib/assets/favicon.svg';
	import { page } from '$app/state';

	let { children } = $props();

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
		clock: 'M12 7v5l3 2M12 22a10 10 0 1 1 0-20 10 10 0 0 1 0 20z'
	};
</script>

<svelte:head><link rel="icon" href={favicon} /></svelte:head>

<div class="flex h-screen w-screen overflow-hidden bg-slate-50 text-slate-900">
	<aside class="flex w-56 flex-col border-r border-slate-200 bg-white">
		<div class="flex items-center gap-2.5 px-5 py-5 border-b border-slate-200">
			<div class="flex h-8 w-8 items-center justify-center rounded-lg bg-slate-900 text-white text-sm font-bold">
				sd
			</div>
			<span class="text-sm font-semibold tracking-tight text-slate-900">safe-db</span>
		</div>

		<nav class="flex flex-1 flex-col gap-1 p-3">
			{#each navItems as item (item.href)}
				<a
					href={item.href}
					class="flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors
					{isActive(item.href)
						? 'bg-slate-900 text-white'
						: 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'}"
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

		<div class="border-t border-slate-200 p-3">
			<div class="rounded-lg bg-slate-50 px-3 py-2.5">
				<p class="text-xs font-medium text-slate-500">Safe Read Mode</p>
				<p class="mt-0.5 text-xs text-slate-400">No-lock · Indexed joins</p>
			</div>
		</div>
	</aside>

	<main class="flex flex-1 flex-col overflow-hidden">
		{@render children()}
	</main>
</div>
