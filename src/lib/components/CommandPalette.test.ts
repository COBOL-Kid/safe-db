import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { goto } from '$app/navigation';
import CommandPalette from '$lib/components/CommandPalette.svelte';
import { connections } from '$lib/stores/connections.svelte';
import { savedQueries } from '$lib/stores/saved-queries.svelte';
import { query } from '$lib/stores/query.svelte';
import * as api from '$lib/api';
import type { ConnectionDef, TableInfo } from '$lib/ir';

vi.mock('$lib/api');
vi.mock('$app/navigation', () => ({
	goto: vi.fn(),
	beforeNavigate: vi.fn(),
	afterNavigate: vi.fn()
}));

const gotoMock = vi.mocked(goto);

const connA: ConnectionDef = {
	id: 'c1',
	name: 'Production',
	dialect: 'Postgres',
	host: 'localhost',
	port: 5432,
	database: 'app',
	username: 'app'
};

const connB: ConnectionDef = {
	id: 'c2',
	name: 'Staging MySQL',
	dialect: 'MySql',
	host: 'localhost',
	port: 3306,
	database: 'app_staging',
	username: 'root'
};

const sampleTable: TableInfo = {
	schema: 'public',
	name: 'users',
	columns: [{ name: 'id', data_type: 'int', nullable: false, is_indexed: true }],
	indexes: []
};

describe('CommandPalette', () => {
	beforeEach(() => {
		vi.mocked(api.listSavedQueries).mockReset();
		vi.mocked(api.listSavedQueries).mockResolvedValue([]);
		vi.mocked(api.listHistory).mockReset();
		vi.mocked(api.listHistory).mockResolvedValue([]);
		vi.mocked(api.runQuery).mockReset();
		vi.mocked(api.runQuery).mockResolvedValue({
			columns: [],
			rows: [],
			row_count: 0,
			truncated: false,
			warnings: []
		});
		gotoMock.mockReset();
		connections.connections = [];
		connections.activeId = null;
		query.clear();
	});

	afterEach(() => {
		cleanup();
	});

	it('does not render the dialog when closed', () => {
		render(CommandPalette, { open: false });
		expect(screen.queryByRole('dialog', { name: 'Command palette' })).not.toBeInTheDocument();
	});

	it('opens on Cmd+K (Meta) and toggles on subsequent presses', async () => {
		const user = userEvent.setup();
		render(CommandPalette, { open: false });

		// Initial state: closed
		expect(screen.queryByRole('dialog', { name: 'Command palette' })).not.toBeInTheDocument();

		// Cmd+K opens
		await user.keyboard('{Meta>}k{/Meta}');
		await waitFor(() => {
			expect(screen.getByRole('dialog', { name: 'Command palette' })).toBeInTheDocument();
		});

		// Cmd+K again closes
		await user.keyboard('{Meta>}k{/Meta}');
		await waitFor(() => {
			expect(screen.queryByRole('dialog', { name: 'Command palette' })).not.toBeInTheDocument();
		});

		// Ctrl+K also opens
		await user.keyboard('{Control>}k{/Control}');
		await waitFor(() => {
			expect(screen.getByRole('dialog', { name: 'Command palette' })).toBeInTheDocument();
		});
	});

	it('Escape closes the dialog', async () => {
		render(CommandPalette, { open: true });
		const dialog = await screen.findByRole('dialog', { name: 'Command palette' });
		expect(dialog).toBeInTheDocument();

		fireEvent.keyDown(dialog, { key: 'Escape' });

		await waitFor(() => {
			expect(screen.queryByRole('dialog', { name: 'Command palette' })).not.toBeInTheDocument();
		});
	});

	it('clicking the backdrop closes; click inside the dialog does not', async () => {
		render(CommandPalette, { open: true });
		const dialog = await screen.findByRole('dialog', { name: 'Command palette' });

		// Click inside the dialog — should not close (the inner div stops propagation).
		fireEvent.click(dialog);
		expect(screen.queryByRole('dialog', { name: 'Command palette' })).toBeInTheDocument();

		// Click on the backdrop (presentation role) — closes.
		const backdrop = screen.getByRole('presentation');
		fireEvent.click(backdrop);
		await waitFor(() => {
			expect(screen.queryByRole('dialog', { name: 'Command palette' })).not.toBeInTheDocument();
		});
	});

	it('renders the four static navigation commands', async () => {
		render(CommandPalette, { open: true });

		expect(await screen.findByText('Go to Home')).toBeInTheDocument();
		expect(screen.getByText('Go to Connections')).toBeInTheDocument();
		expect(screen.getByText('Go to Query Builder')).toBeInTheDocument();
		expect(screen.getByText('Go to History')).toBeInTheDocument();
	});

	it('shows Run Query / Clear Canvas only when there is an active connection with tables', async () => {
		const { rerender } = render(CommandPalette, { open: true });

		// No active connection yet: no run/clear.
		expect(screen.queryByText('Run Query')).not.toBeInTheDocument();
		expect(screen.queryByText('Clear Canvas')).not.toBeInTheDocument();

		// Active connection but no tables: still none.
		connections.connections = [connA];
		connections.activeId = 'c1';
		await rerender({ open: true });
		expect(screen.queryByText('Run Query')).not.toBeInTheDocument();
		expect(screen.queryByText('Clear Canvas')).not.toBeInTheDocument();

		// Add a table: run + clear appear.
		query.addTable(sampleTable);
		await rerender({ open: true });
		expect(await screen.findByText('Run Query')).toBeInTheDocument();
		expect(screen.getByText('Clear Canvas')).toBeInTheDocument();
	});

	it('renders one Explore command per connection', async () => {
		connections.connections = [connA, connB];
		render(CommandPalette, { open: true });

		expect(await screen.findByText('Explore: Production')).toBeInTheDocument();
		expect(screen.getByText('Explore: Staging MySQL')).toBeInTheDocument();
		// The hint uses the raw dialect enum value, not the display label.
		expect(screen.getByText('Postgres · app')).toBeInTheDocument();
		expect(screen.getByText('MySql · app_staging')).toBeInTheDocument();
	});

	it('filter is case-insensitive on label and hint, and "no commands found" shows when no match', async () => {
		connections.connections = [connA];
		const user = userEvent.setup();
		render(CommandPalette, { open: true });
		const search = (await screen.findByLabelText('Command search')) as HTMLInputElement;

		// Case-insensitive label match
		await user.type(search, 'hOmE');
		expect(screen.getByText('Go to Home')).toBeInTheDocument();
		expect(screen.queryByText('Go to Connections')).not.toBeInTheDocument();

		// Hint match (clear first so we don't keep the previous characters).
		await user.clear(search);
		await user.type(search, 'manage');
		expect(screen.getByText('Go to Connections')).toBeInTheDocument();
		expect(screen.queryByText('Go to Home')).not.toBeInTheDocument();

		// No matches
		await user.clear(search);
		await user.type(search, 'zzz nothing');
		expect(screen.getByText('No commands found')).toBeInTheDocument();

		// Clear filter restores all
		await user.clear(search);
		expect(screen.getByText('Go to Home')).toBeInTheDocument();
	});

	it('ArrowDown / ArrowUp move selectedIndex clamped at the ends', async () => {
		connections.connections = [connA];
		const { rerender } = render(CommandPalette, { open: true });
		const dialog = await screen.findByRole('dialog', { name: 'Command palette' });

		// 4 static + 1 explore = 5 commands
		const total = 5;

		// At top: ArrowUp stays at 0
		fireEvent.keyDown(dialog, { key: 'ArrowUp' });
		await rerender({ open: true });
		// No reliable DOM signal of the selected index; verify it caps at the end:
		// Press ArrowDown `total + 2` times and confirm it doesn't crash and
		// the dialog stays open.
		for (let i = 0; i < total + 2; i++) {
			fireEvent.keyDown(dialog, { key: 'ArrowDown' });
		}
		expect(dialog).toBeInTheDocument();

		// And the last command is the Explore one (selected after clamping).
		// Pressing Enter on the last selected invokes its action.
		fireEvent.keyDown(dialog, { key: 'Enter' });

		await waitFor(() => {
			expect(connections.activeId).toBe('c1');
		});
		expect(gotoMock).toHaveBeenCalledWith('/builder');
	});

	it('Enter on the first command invokes its action and closes the dialog', async () => {
		render(CommandPalette, { open: true });
		const dialog = await screen.findByRole('dialog', { name: 'Command palette' });

		// First command is "Go to Home".
		fireEvent.keyDown(dialog, { key: 'Enter' });

		await waitFor(() => {
			expect(gotoMock).toHaveBeenCalledWith('/');
		});
		await waitFor(() => {
			expect(screen.queryByRole('dialog', { name: 'Command palette' })).not.toBeInTheDocument();
		});
	});

	it('Run Query invokes query.run(activeId) and navigates to /builder', async () => {
		connections.connections = [connA];
		connections.activeId = 'c1';
		query.addTable(sampleTable);
		render(CommandPalette, { open: true });

		const run = await screen.findByText('Run Query');
		fireEvent.click(run.closest('button')!);

		await waitFor(() => {
			expect(api.runQuery).toHaveBeenCalled();
		});
		const [connId, spec] = vi.mocked(api.runQuery).mock.calls[0];
		expect(connId).toBe('c1');
		expect(spec.tables).toHaveLength(1);
		expect(gotoMock).toHaveBeenCalledWith('/builder');
	});

	it('Clear Canvas invokes query.clear() and closes', async () => {
		connections.connections = [connA];
		connections.activeId = 'c1';
		query.addTable(sampleTable);
		render(CommandPalette, { open: true });

		const clear = await screen.findByText('Clear Canvas');
		fireEvent.click(clear.closest('button')!);

		await waitFor(() => {
			expect(query.tables).toHaveLength(0);
		});
		await waitFor(() => {
			expect(screen.queryByRole('dialog', { name: 'Command palette' })).not.toBeInTheDocument();
		});
	});

	it('Explore: <name> sets the active connection and navigates', async () => {
		connections.connections = [connA, connB];
		render(CommandPalette, { open: true });

		const exploreB = await screen.findByText('Explore: Staging MySQL');
		fireEvent.click(exploreB.closest('button')!);

		await waitFor(() => {
			expect(connections.activeId).toBe('c2');
		});
		expect(gotoMock).toHaveBeenCalledWith('/builder');
	});

	it('opening the palette resets search and selectedIndex', async () => {
		const { rerender } = render(CommandPalette, { open: true });
		const search = await screen.findByLabelText('Command search');

		// Type something.
		fireEvent.input(search, { target: { value: 'home' } });
		expect((search as HTMLInputElement).value).toBe('home');

		// Close then open.
		await rerender({ open: false });
		await rerender({ open: true });

		const searchAfter = await screen.findByLabelText('Command search');
		expect((searchAfter as HTMLInputElement).value).toBe('');
		// All commands visible (filter cleared)
		expect(screen.getByText('Go to Home')).toBeInTheDocument();
		expect(screen.getByText('Go to Connections')).toBeInTheDocument();
	});
});
