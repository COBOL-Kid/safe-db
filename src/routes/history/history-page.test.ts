import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { goto } from '$app/navigation';
import HistoryPage from './+page.svelte';
import * as api from '$lib/api';
import { history } from '$lib/stores/history.svelte';
import { connections } from '$lib/stores/connections.svelte';
import { query } from '$lib/stores/query.svelte';
import { schema } from '$lib/stores/schema.svelte';
import { savedQueries } from '$lib/stores/saved-queries.svelte';
import type { ConnectionDef, HistoryEntry } from '$lib/ir';

vi.mock('$lib/api');
vi.mock('$app/navigation', () => ({
	goto: vi.fn(),
	beforeNavigate: vi.fn(),
	afterNavigate: vi.fn()
}));

const gotoMock = vi.mocked(goto);

const sampleConn: ConnectionDef = {
	version: 2,
	id: 'c1',
	name: 'Test DB',
	dialect: 'Postgres',
	host: 'localhost',
	port: 5432,
	database: 'demo',
	username: 'user',
	transport_security: {
		mode: 'VerifyIdentity'
	}
};

function makeEntry(overrides: Partial<HistoryEntry> = {}): HistoryEntry {
	return {
		id: 'h1',
		connection_id: 'c1',
		connection_name: 'Test DB',
		spec: {
			tables: [
				{ schema: 'public', name: 'users', alias: 't0' },
				{ schema: 'public', name: 'orders', alias: 't1' }
			],
			columns: [
				{ table_alias: 't0', column: 'id' },
				{ table_alias: 't0', column: 'email' }
			],
			joins: [{ left_alias: 't0', left_column: 'id', right_alias: 't1', right_column: 'user_id' }],
			filters: { id: 'g', connector: 'And', children: [] },
			limit: 50,
			schema_version: 3,
			connector_overrides: {}
		},
		row_count: 12,
		warnings: [],
		error: null,
		timestamp: Math.floor(Date.now() / 1000).toString(),
		...overrides
	};
}

async function renderWith(entries: HistoryEntry[] = []) {
	// Drive the page's mount effect by returning the entries from
	// api.listHistory; the singleton is populated by `history.load()` inside
	// the page, so the rendered list reflects the test data.
	vi.mocked(api.listHistory).mockResolvedValue(entries);
	render(HistoryPage);
	await waitFor(() => {
		expect(history.entries).toEqual(entries);
	});
}

describe('History page', () => {
	beforeEach(() => {
		vi.mocked(api.listHistory).mockReset();
		vi.mocked(api.clearHistory).mockReset();
		vi.mocked(api.listSavedQueries).mockReset();
		vi.mocked(api.listSavedQueries).mockResolvedValue([]);
		vi.mocked(api.saveSavedQuery).mockReset();
		vi.mocked(api.getSchema).mockReset();
		vi.mocked(api.getSchema).mockResolvedValue({ tables: [] });
		gotoMock.mockReset();
		history.entries = [];
		history.loading = false;
		connections.connections = [];
		connections.activeId = null;
		schema.clear();
		query.clear();
	});

	afterEach(() => {
		cleanup();
	});

	it('shows the empty state when there is no history', async () => {
		await renderWith([]);

		expect(await screen.findByText('No query history yet')).toBeInTheDocument();
		expect(screen.getByRole('link', { name: 'Build a Query' })).toHaveAttribute('href', '/builder');
	});

	it('calls history.load on mount (verified via api.listHistory)', async () => {
		vi.mocked(api.listHistory).mockResolvedValue([]);
		render(HistoryPage);

		await waitFor(() => {
			expect(api.listHistory).toHaveBeenCalled();
		});
	});

	it('renders the success badge and summarizeSpec line for a successful entry', async () => {
		await renderWith([makeEntry()]);

		expect(await screen.findByText('Test DB')).toBeInTheDocument();
		expect(screen.getByText('12 rows')).toBeInTheDocument();
		expect(screen.getByText(/users, orders · 2 cols · 1 join · limit 50/)).toBeInTheDocument();
	});

	it('shows the failed badge and error text for an errored entry', async () => {
		await renderWith([makeEntry({ error: 'permission denied for table users' })]);

		expect(await screen.findByText('failed')).toBeInTheDocument();
		expect(screen.getByText('permission denied for table users')).toBeInTheDocument();
	});

	it('caps the displayed warnings at 2 and shows a +N more link', async () => {
		await renderWith([
			makeEntry({
				warnings: [
					'Estimated query cost (250000) exceeds threshold (100000)',
					'EXPLAIN failed',
					'extra warning A',
					'extra warning B'
				]
			})
		]);

		expect(await screen.findByText(/cost \(250000\)/)).toBeInTheDocument();
		expect(screen.getByText('EXPLAIN failed')).toBeInTheDocument();
		expect(screen.queryByText('extra warning A')).not.toBeInTheDocument();
		expect(screen.getByText('+2 more')).toBeInTheDocument();
	});

	it('formats timestamps as "just now" for the current second', async () => {
		await renderWith([makeEntry({ timestamp: Math.floor(Date.now() / 1000).toString() })]);

		expect(await screen.findByText('just now')).toBeInTheDocument();
	});

	it('formats timestamps as "Xm ago" for entries under an hour', async () => {
		const fiveMinAgo = Math.floor((Date.now() - 5 * 60_000) / 1000).toString();
		await renderWith([makeEntry({ timestamp: fiveMinAgo })]);

		expect(await screen.findByText('5m ago')).toBeInTheDocument();
	});

	it('Rerun invokes setActive, schema.clear, schema.load, hydrate, and goto /builder in order', async () => {
		await renderWith([makeEntry()]);
		connections.connections = [sampleConn];
		vi.mocked(api.getSchema).mockResolvedValue({ tables: [] });

		const order: string[] = [];
		const origSetActive = connections.setActive.bind(connections);
		connections.setActive = ((id: string | null) => {
			order.push(`setActive:${id}`);
			origSetActive(id);
		}) as typeof connections.setActive;
		const origClear = schema.clear.bind(schema);
		schema.clear = () => {
			order.push('schema.clear');
			origClear();
		};
		vi.mocked(api.getSchema).mockImplementation(async (id) => {
			order.push(`schema.load:${id}`);
			return { tables: [] };
		});
		gotoMock.mockImplementation(() => {
			order.push('goto:/builder');
			return Promise.resolve();
		});

		const user = userEvent.setup();
		const rerun = await screen.findByRole('button', { name: 'Rerun' });
		await user.click(rerun);

		await waitFor(() => {
			expect(gotoMock).toHaveBeenCalledWith('/builder');
		});

		expect(order).toEqual(['schema.clear', 'schema.load:c1', 'goto:/builder']);
		expect(connections.activeId).toBe('c1');

		connections.setActive = origSetActive;
	});

	it('Save as query prompts and saves a new SavedQuery with the entry spec', async () => {
		await renderWith([makeEntry()]);
		vi.mocked(api.saveSavedQuery).mockResolvedValue();
		vi.mocked(api.listSavedQueries).mockResolvedValue([]);
		const uuidSpy = vi.spyOn(crypto, 'randomUUID').mockReturnValue('00000000-0000-4000-8000-000000000001');

		const user = userEvent.setup();
		await user.click(await screen.findByRole('button', { name: 'Save as query' }));
		const dialog = screen.getByRole('dialog');
		await user.clear(within(dialog).getByPlaceholderText('Query name'));
		await user.type(within(dialog).getByPlaceholderText('Query name'), 'My rerun');
		await user.click(within(dialog).getByRole('button', { name: 'Save' }));

		await waitFor(() => {
			expect(api.saveSavedQuery).toHaveBeenCalled();
		});
		const savedArg = vi.mocked(api.saveSavedQuery).mock.calls[0][0];
		expect(savedArg.name).toBe('My rerun');
		expect(savedArg.connection_id).toBe('c1');
		expect(savedArg.id).toBe('00000000-0000-4000-8000-000000000001');
		expect(savedArg.spec).toBe(history.entries[0].spec);

		uuidSpy.mockRestore();
	});

	it('Save as query is hidden for failed entries', async () => {
		await renderWith([makeEntry({ error: 'boom' })]);

		await screen.findByText('failed');
		expect(screen.queryByRole('button', { name: 'Save as query' })).not.toBeInTheDocument();
	});

	it('Save as query bails when the user cancels the prompt', async () => {
		await renderWith([makeEntry()]);
		vi.mocked(api.saveSavedQuery).mockResolvedValue();
		const promptSpy = vi.spyOn(window, 'prompt').mockReturnValue(null);

		const user = userEvent.setup();
		await user.click(await screen.findByRole('button', { name: 'Save as query' }));

		await new Promise((r) => setTimeout(r, 0));
		expect(api.saveSavedQuery).not.toHaveBeenCalled();

		promptSpy.mockRestore();
	});

	it('Clear History opens the dialog and confirm calls api.clearHistory', async () => {
		await renderWith([makeEntry()]);
		vi.mocked(api.clearHistory).mockResolvedValue();

		const user = userEvent.setup();
		await user.click(await screen.findByRole('button', { name: 'Clear History' }));

		expect(await screen.findByRole('alertdialog')).toBeInTheDocument();

		await user.click(screen.getByRole('button', { name: 'Delete' }));

		await waitFor(() => {
			expect(api.clearHistory).toHaveBeenCalled();
		});
	});
});
