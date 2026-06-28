import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { goto } from '$app/navigation';
import HomePage from './+page.svelte';
import * as api from '$lib/api';
import { savedQueries } from '$lib/stores/saved-queries.svelte';
import { connections } from '$lib/stores/connections.svelte';
import { schema } from '$lib/stores/schema.svelte';
import { query } from '$lib/stores/query.svelte';
import type { ConnectionDef, SavedQuery } from '$lib/ir';

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
		mode: 'VerifyIdentity',
		insecure_acknowledged: false
	}
};

function makeSaved(id: string, name: string, connectionId = 'c1'): SavedQuery {
	return {
		id,
		name,
		connection_id: connectionId,
		spec: {
			tables: [{ schema: 'public', name: 'users', alias: 't0' }],
			columns: [{ table_alias: 't0', column: 'id' }],
			joins: [],
			filters: { id: 'g', connector: 'And', children: [] },
			limit: 100,
			schema_version: 3,
			connector_overrides: {}
		},
		created_at: '1700000000'
	};
}

describe('Home page', () => {
	beforeEach(() => {
		vi.mocked(api.listSavedQueries).mockReset();
		vi.mocked(api.listSavedQueries).mockResolvedValue([]);
		vi.mocked(api.listConnections).mockReset();
		vi.mocked(api.listConnections).mockResolvedValue([]);
		vi.mocked(api.deleteSavedQuery).mockReset();
		vi.mocked(api.getSchema).mockReset();
		vi.mocked(api.getSchema).mockResolvedValue({ tables: [] });
		gotoMock.mockReset();
		savedQueries.queries = [];
		connections.connections = [];
		connections.activeId = null;
		schema.clear();
		query.clear();
	});

	afterEach(() => {
		cleanup();
	});

	it('renders the three action cards', () => {
		render(HomePage);

		expect(screen.getByText('New Connection')).toBeInTheDocument();
		expect(screen.getByText('Build a Query')).toBeInTheDocument();
		expect(screen.getByText('Recent Queries')).toBeInTheDocument();
	});

	it('hides the Saved Queries section when none exist', () => {
		render(HomePage);

		expect(screen.queryByText('Saved Queries')).not.toBeInTheDocument();
	});

	it('calls savedQueries.load and connections.load on mount', async () => {
		vi.mocked(api.listSavedQueries).mockResolvedValue([]);
		vi.mocked(api.listConnections).mockResolvedValue([sampleConn]);

		render(HomePage);

		await waitFor(() => {
			expect(api.listSavedQueries).toHaveBeenCalled();
		});
		await waitFor(() => {
			expect(api.listConnections).toHaveBeenCalled();
		});
		await waitFor(() => {
			expect(connections.connections).toEqual([sampleConn]);
		});
	});

	it('renders saved queries with the connection name and table count', async () => {
		vi.mocked(api.listSavedQueries).mockResolvedValue([
			makeSaved('q1', 'Active users')
		]);
		vi.mocked(api.listConnections).mockResolvedValue([sampleConn]);

		render(HomePage);

		expect(await screen.findByText('Active users')).toBeInTheDocument();
		expect(screen.getByText(/Test DB · 1 table/)).toBeInTheDocument();
	});

	it('connName falls back to "Unknown" when the connection id is not in the list', async () => {
		vi.mocked(api.listSavedQueries).mockResolvedValue([
			makeSaved('q1', 'Orphan', 'missing')
		]);
		vi.mocked(api.listConnections).mockResolvedValue([sampleConn]);

		render(HomePage);

		expect(await screen.findByText('Orphan')).toBeInTheDocument();
		expect(screen.getByText(/Unknown · 1 table/)).toBeInTheDocument();
	});

	it('clicking a saved query invokes setActive, schema.clear, schema.load, hydrate, and goto /builder in order', async () => {
		vi.mocked(api.listSavedQueries).mockResolvedValue([
			makeSaved('q1', 'Active users')
		]);
		vi.mocked(api.listConnections).mockResolvedValue([sampleConn]);
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

		render(HomePage);

		const user = userEvent.setup();
		await user.click(await screen.findByText('Active users'));

		await waitFor(() => {
			expect(gotoMock).toHaveBeenCalledWith('/builder');
		});

		expect(order).toEqual(['schema.clear', 'schema.load:c1', 'goto:/builder']);
		expect(connections.activeId).toBe('c1');

		connections.setActive = origSetActive;
	});

	it('Delete button on a saved query card calls savedQueries.remove after confirm', async () => {
		vi.mocked(api.listSavedQueries).mockResolvedValue([
			makeSaved('q1', 'Active users')
		]);
		vi.mocked(api.listConnections).mockResolvedValue([sampleConn]);
		vi.mocked(api.deleteSavedQuery).mockResolvedValue();

		render(HomePage);

		const user = userEvent.setup();
		await user.click(await screen.findByRole('button', { name: 'Delete saved query' }));
		await user.click(screen.getByRole('button', { name: 'Delete' }));

		await waitFor(() => {
			expect(api.deleteSavedQuery).toHaveBeenCalledWith('q1');
		});
	});
});
