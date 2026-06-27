import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import ConnectionsPage from './+page.svelte';
import * as api from '$lib/api';

vi.mock('$lib/api');

const sampleConnection = {
	id: 'c1',
	name: 'Test DB',
	dialect: 'Postgres' as const,
	host: 'localhost',
	port: 5432,
	database: 'demo',
	username: 'user'
};

describe('Connections page delete flow', () => {
	afterEach(() => {
		cleanup();
		vi.mocked(api.listConnections).mockReset();
		vi.mocked(api.deleteConnection).mockReset();
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
});
