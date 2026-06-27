import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { goto } from '$app/navigation';
import ConnectionsPage from './+page.svelte';
import { connections } from '$lib/stores/connections.svelte';
import { schema } from '$lib/stores/schema.svelte';
import * as api from '$lib/api';

vi.mock('$lib/api');
vi.mock('$app/navigation', () => ({
	goto: vi.fn(),
	beforeNavigate: vi.fn(),
	afterNavigate: vi.fn()
}));

const gotoMock = vi.mocked(goto);

const sampleConnection = {
	id: 'c1',
	name: 'Test DB',
	dialect: 'Postgres' as const,
	host: 'localhost',
	port: 5432,
	database: 'demo',
	username: 'user'
};

const origSetActive = connections.setActive.bind(connections);
const origClear = schema.clear.bind(schema);

describe('Connections page', () => {
	afterEach(() => {
		cleanup();
		vi.mocked(api.listConnections).mockReset();
		vi.mocked(api.deleteConnection).mockReset();
		vi.mocked(api.getSchema).mockReset();
		gotoMock.mockReset();
		// Restore any store-method overrides installed by individual tests so
		// an earlier assertion failure can't poison the next test.
		connections.setActive = origSetActive;
		schema.clear = origClear;
	});

	it('loads connections on mount', async () => {
		vi.mocked(api.listConnections).mockResolvedValue([sampleConnection]);

		render(ConnectionsPage);

		await waitFor(() => {
			expect(api.listConnections).toHaveBeenCalled();
		});
		expect(await screen.findByText('Test DB')).toBeInTheDocument();
	});

	it('deletes connection after in-app confirmation', async () => {
		const user = userEvent.setup();
		vi.mocked(api.listConnections).mockResolvedValue([sampleConnection]);
		vi.mocked(api.deleteConnection).mockResolvedValue();

		render(ConnectionsPage);

		await waitFor(() => {
			expect(screen.getByText('Test DB')).toBeInTheDocument();
		});

		await user.click(screen.getByRole('button', { name: 'Delete connection' }));
		expect(screen.getByRole('alertdialog')).toBeInTheDocument();
		expect(screen.getByText(/Delete connection "Test DB"/)).toBeInTheDocument();

		await user.click(screen.getByRole('button', { name: 'Delete' }));

		await waitFor(() => {
			expect(api.deleteConnection).toHaveBeenCalledWith('c1');
		});
		expect(screen.queryByText('Test DB')).not.toBeInTheDocument();
	});

	it('shows the empty state and toggles the form when clicking Add Connection', async () => {
		const user = userEvent.setup();
		vi.mocked(api.listConnections).mockResolvedValue([]);

		render(ConnectionsPage);

		await waitFor(() => {
			expect(screen.getByText('No connections yet')).toBeInTheDocument();
		});

		// Two "Add Connection" buttons (header + empty state) — pick the empty-state one.
		const addButtons = screen.getAllByRole('button', { name: 'Add Connection' });
		await user.click(addButtons[addButtons.length - 1]);
		// ConnectionForm exposes a "Test Connection" button when shown.
		expect(await screen.findByRole('button', { name: 'Test Connection' })).toBeInTheDocument();
	});

	it('Open invokes setActive, schema.clear, schema.load, then goto /builder in order', async () => {
		const user = userEvent.setup();
		vi.mocked(api.listConnections).mockResolvedValue([sampleConnection]);
		vi.mocked(api.getSchema).mockResolvedValue({ tables: [] });

		const order: string[] = [];
		connections.setActive = ((id: string | null) => {
			order.push(`setActive:${id}`);
			origSetActive(id);
		}) as typeof connections.setActive;
		schema.clear = () => {
			order.push('schema.clear');
			origClear();
		};
		vi.mocked(api.getSchema).mockImplementation(async () => {
			order.push('schema.load');
			return { tables: [] };
		});
		gotoMock.mockImplementation(() => {
			order.push('goto:/builder');
			return Promise.resolve();
		});

		render(ConnectionsPage);

		await waitFor(() => {
			expect(screen.getByText('Test DB')).toBeInTheDocument();
		});

		await user.click(screen.getByRole('button', { name: 'Open' }));

		await waitFor(() => {
			expect(api.getSchema).toHaveBeenCalledWith('c1');
		});
		await waitFor(() => {
			expect(gotoMock).toHaveBeenCalledWith('/builder');
		});

		expect(order).toEqual([
			'setActive:c1',
			'schema.clear',
			'schema.load',
			'goto:/builder'
		]);
	});

	it('still navigates to /builder when schema.load fails (current behavior: load catches its own errors)', async () => {
		const user = userEvent.setup();
		vi.mocked(api.listConnections).mockResolvedValue([sampleConnection]);
		vi.mocked(api.getSchema).mockRejectedValue(new Error('no schema'));

		render(ConnectionsPage);

		await waitFor(() => {
			expect(screen.getByText('Test DB')).toBeInTheDocument();
		});

		await user.click(screen.getByRole('button', { name: 'Open' }));

		await waitFor(() => {
			expect(api.getSchema).toHaveBeenCalledWith('c1');
		});
		// schema.load swallows errors, so handleOpen continues and calls goto.
		await waitFor(() => {
			expect(gotoMock).toHaveBeenCalledWith('/builder');
		});
	});
});
