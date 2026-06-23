<script lang="ts">
	const actions = [
		{ href: '/connections', title: 'New Connection', desc: 'Connect to a database', icon: 'M12 5v14M5 12h14' },
		{ href: '/builder', title: 'Build a Query', desc: 'Visually explore your data', icon: 'M4 6h16M4 12h16M4 18h10' },
		{ href: '/history', title: 'Recent Queries', desc: 'Revisit past explorations', icon: 'M12 8v4l3 2M12 22a10 10 0 1 1 0-20 10 10 0 0 1 0 20z' }
	];
</script>

<div class="flex flex-1 flex-col overflow-y-auto">
	<div class="mx-auto w-full max-w-4xl px-8 py-12">
		<h1 class="text-3xl font-bold tracking-tight text-slate-900">Welcome to safe-db</h1>
		<p class="mt-2 text-base text-slate-500">
			Safely explore production databases with non-locking reads and enforced best practices.
		</p>

		<div class="mt-10 grid grid-cols-1 gap-4 sm:grid-cols-3">
			{#each actions as action (action.href)}
				<a
					href={action.href}
					class="group flex flex-col rounded-xl border border-slate-200 bg-white p-5 transition-all hover:border-slate-300 hover:shadow-md"
				>
					<div
						class="flex h-10 w-10 items-center justify-center rounded-lg bg-slate-100 text-slate-600 transition-colors group-hover:bg-slate-900 group-hover:text-white"
					>
						<svg
							class="h-5 w-5"
							viewBox="0 0 24 24"
							fill="none"
							stroke="currentColor"
							stroke-width="1.8"
							stroke-linecap="round"
							stroke-linejoin="round"
						>
							<path d={action.icon} />
						</svg>
					</div>
					<h3 class="mt-4 text-sm font-semibold text-slate-900">{action.title}</h3>
					<p class="mt-1 text-sm text-slate-500">{action.desc}</p>
				</a>
			{/each}
		</div>

		<div class="mt-12 rounded-xl border border-slate-200 bg-white p-6">
			<h2 class="text-sm font-semibold text-slate-900">How safe-db protects your database</h2>
			<div class="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
				<div class="flex gap-3">
					<div class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-emerald-600">
						<svg class="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5L20 7" /></svg>
					</div>
					<div>
						<p class="text-sm font-medium text-slate-700">Non-locking reads</p>
						<p class="text-sm text-slate-400">Dirty-read tolerant isolation — never blocks production writes.</p>
					</div>
				</div>
				<div class="flex gap-3">
					<div class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-emerald-600">
						<svg class="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5L20 7" /></svg>
					</div>
					<div>
						<p class="text-sm font-medium text-slate-700">Indexed joins only</p>
						<p class="text-sm text-slate-400">Joins on non-indexed columns are rejected before execution.</p>
					</div>
				</div>
				<div class="flex gap-3">
					<div class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-emerald-600">
						<svg class="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5L20 7" /></svg>
					</div>
					<div>
						<p class="text-sm font-medium text-slate-700">Capped row limits & timeouts</p>
						<p class="text-sm text-slate-400">Every query is bounded — no runaway full-table scans.</p>
					</div>
				</div>
				<div class="flex gap-3">
					<div class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-emerald-600">
						<svg class="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5L20 7" /></svg>
					</div>
					<div>
						<p class="text-sm font-medium text-slate-700">Read-only by construction</p>
						<p class="text-sm text-slate-400">Writes are structurally impossible — no SQL injection surface.</p>
					</div>
				</div>
			</div>
		</div>
	</div>
</div>
