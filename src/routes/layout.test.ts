import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import Layout from './+layout.svelte';
import * as api from '$lib/api';
import { settings } from '$lib/stores/settings.svelte';
import { connections } from '$lib/stores/connections.svelte';
import { savedQueries } from '$lib/stores/saved-queries.svelte';
import { history } from '$lib/stores/history.svelte';

vi.mock('$lib/api');
vi.mock('$app/navigation', () => ({
	goto: vi.fn(),
	beforeNavigate: vi.fn(),
	afterNavigate: vi.fn()
}));

const pageState = { url: { pathname: '/' } };
vi.mock('$app/state', () => ({
	get page() {
		return pageState;
	}
}));

describe('Layout', () => {
	beforeEach(() => {
		vi.mocked(api.getSettings).mockReset();
		vi.mocked(api.getSettings).mockResolvedValue({
			blocked_schemas: [],
			explain_cost_threshold: 100_000,
			theme: 'light'
		});
		vi.mocked(api.saveSettings).mockReset();
		vi.mocked(api.saveSettings).mockResolvedValue();
		vi.mocked(api.listConnections).mockReset();
		vi.mocked(api.listConnections).mockResolvedValue([]);
		vi.mocked(api.listSavedQueries).mockReset();
		vi.mocked(api.listSavedQueries).mockResolvedValue([]);
		vi.mocked(api.listHistory).mockReset();
		vi.mocked(api.listHistory).mockResolvedValue([]);

		settings.settings = { blocked_schemas: [], explain_cost_threshold: 100_000, theme: 'light' };
		settings.loading = false;
		connections.connections = [];
		connections.activeId = null;
		savedQueries.queries = [];
		history.entries = [];
		pageState.url = { pathname: '/' };
	});

	afterEach(() => {
		cleanup();
	});

	function mount() {
		// `children` is a snippet prop on the layout; pass a no-op so the
		// `{@render children()}` call resolves. We intentionally don't pass
		// any DOM so the layout's sidebar/main is the only thing rendered.
		return render(Layout, {
			children: (() => null) as never
		});
	}

	it('renders the sidebar with all four nav items', () => {
		mount();

		expect(screen.getByRole('link', { name: /Home/ })).toBeInTheDocument();
		expect(screen.getByRole('link', { name: /Connections/ })).toBeInTheDocument();
		expect(screen.getByRole('link', { name: /Query Builder/ })).toBeInTheDocument();
		expect(screen.getByRole('link', { name: /History/ })).toBeInTheDocument();
	});

	it('highlights the nav link matching the current path', () => {
		pageState.url = { pathname: '/connections' };
		mount();

		const connectionsLink = screen.getByRole('link', { name: /Connections/ });
		// The active link has the dark background class.
		expect(connectionsLink.className).toContain('bg-slate-900');
	});

	it('Home is only active on exact "/" (not on "/connections" etc.)', () => {
		pageState.url = { pathname: '/connections' };
		mount();

		const homeLink = screen.getByRole('link', { name: /Home/ });
		expect(homeLink.className).not.toContain('bg-slate-900');
	});

	it('mount effect calls all four store load methods', async () => {
		mount();

		await waitFor(() => {
			expect(api.getSettings).toHaveBeenCalled();
			expect(api.listConnections).toHaveBeenCalled();
			expect(api.listSavedQueries).toHaveBeenCalled();
			expect(api.listHistory).toHaveBeenCalled();
		});
	});

	it('Toggle theme button flips light → dark and persists', async () => {
		const user = userEvent.setup();
		mount();

		const toggle = await screen.findByRole('button', { name: 'Toggle theme' });
		expect(settings.isDark).toBe(false);

		await user.click(toggle);

		await waitFor(() => {
			expect(settings.isDark).toBe(true);
			expect(api.saveSettings).toHaveBeenCalled();
			// Persisted settings reflect the flip.
			const last = vi.mocked(api.saveSettings).mock.calls.at(-1);
			expect(last?.[0].theme).toBe('dark');
		});
	});

	it('Toggle theme button flips dark → light and persists', async () => {
		const user = userEvent.setup();
		// Have the settings store load as 'dark' so we start in dark mode.
		vi.mocked(api.getSettings).mockResolvedValue({
			blocked_schemas: [],
			explain_cost_threshold: 100_000,
			theme: 'dark'
		});
		settings.settings = {
			blocked_schemas: [],
			explain_cost_threshold: 100_000,
			theme: 'dark'
		};

		mount();

		const toggle = await screen.findByRole('button', { name: 'Toggle theme' });
		expect(settings.isDark).toBe(true);

		await user.click(toggle);

		await waitFor(() => {
			expect(settings.isDark).toBe(false);
			const last = vi.mocked(api.saveSettings).mock.calls.at(-1);
			expect(last?.[0].theme).toBe('light');
		});
	});

	it('Command button opens the command palette', async () => {
		const user = userEvent.setup();
		mount();

		// Initially the palette dialog is not present.
		expect(screen.queryByRole('dialog', { name: 'Command palette' })).not.toBeInTheDocument();

		await user.click(screen.getByRole('button', { name: /Command/ }));

		await waitFor(() => {
			expect(screen.getByRole('dialog', { name: 'Command palette' })).toBeInTheDocument();
		});
	});

	it('renders the slotted children via the {@render children()} block', () => {
		mount();
		expect(screen.getByRole('main')).toBeInTheDocument();
	});
});
