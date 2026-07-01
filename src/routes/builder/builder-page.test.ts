import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import BuilderPage from './+page.svelte';
import * as api from '$lib/api';
import { connections } from '$lib/stores/connections.svelte';
import { schema } from '$lib/stores/schema.svelte';
import { query } from '$lib/stores/query.svelte';
import { savedQueries } from '$lib/stores/saved-queries.svelte';
import type { ConnectionDef, TableInfo } from '$lib/ir';
import { COST_GUARD_PREFIX } from '$lib/limits';

vi.mock('$lib/api');

const activeConnection: ConnectionDef = {
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

const sampleTable: TableInfo = {
	schema: 'public',
	name: 'users',
	columns: [
		{ name: 'id', data_type: 'int', nullable: false, is_indexed: true },
		{ name: 'email', data_type: 'varchar', nullable: true, is_indexed: false }
	],
	indexes: [{ name: 'users_pkey', columns: ['id'], is_unique: true, is_primary: true }]
};

describe('Builder page', () => {
	beforeEach(() => {
		vi.mocked(api.getSchema).mockReset();
		vi.mocked(api.saveSavedQuery).mockReset();
		vi.mocked(api.listSavedQueries).mockReset();
		vi.mocked(api.listSavedQueries).mockResolvedValue([]);
		vi.mocked(api.listConnections).mockReset();
		vi.mocked(api.listConnections).mockResolvedValue([]);
		connections.connections = [];
		connections.activeId = null;
		schema.clear();
		query.clear();
	});

	afterEach(() => {
		cleanup();
		connections.connections = [];
		connections.activeId = null;
		schema.clear();
		query.clear();
	});

	it('shows the "No connection selected" empty state when no active connection', async () => {
		render(BuilderPage);

		expect(await screen.findByText('No connection selected')).toBeInTheDocument();
		expect(screen.getByRole('link', { name: 'Go to Connections' })).toHaveAttribute('href', '/connections');
		expect(api.getSchema).not.toHaveBeenCalled();
	});

	it('loads the schema on mount when there is an active connection and no schema yet', async () => {
		connections.connections = [activeConnection];
		connections.activeId = 'c1';
		vi.mocked(api.getSchema).mockResolvedValue({ tables: [] });

		render(BuilderPage);

		await waitFor(() => {
			expect(api.getSchema).toHaveBeenCalledWith('c1');
		});
	});

	it('skips schema.load when the schema is already loaded', async () => {
		connections.connections = [activeConnection];
		connections.activeId = 'c1';
		vi.mocked(api.getSchema).mockResolvedValue({ tables: [] });
		schema.load('c1');
		await waitFor(() => {
			expect(schema.schema).not.toBeNull();
		});

		render(BuilderPage);

		// Give the effect a tick to run.
		await new Promise((r) => setTimeout(r, 0));
		expect(api.getSchema).toHaveBeenCalledTimes(1);
	});

	it('Run button is disabled when canRun is false and runs when canRun is true', async () => {
		connections.connections = [activeConnection];
		connections.activeId = 'c1';
		vi.mocked(api.getSchema).mockResolvedValue({ tables: [sampleTable] });
		vi.mocked(api.runQuery).mockReset();
		vi.mocked(api.runQuery).mockResolvedValue({
			columns: [],
			rows: [],
			row_count: 0,
			truncated: false,
			warnings: []
		});

		render(BuilderPage);

		const run = await screen.findByRole('button', { name: /Run Query/ });
		expect(run).toBeDisabled();

		query.addTable(sampleTable);
		await waitFor(() => {
			expect(run).not.toBeDisabled();
		});

		const user = userEvent.setup();
		await user.click(run);

		await waitFor(() => {
			expect(api.runQuery).toHaveBeenCalled();
		});
		const call = vi.mocked(api.runQuery).mock.calls[0];
		expect(call[0]).toBe('c1');
		expect(call[1].tables).toHaveLength(1);
	});

	it('shows friendly copy and retries with safeguards when cost estimate is unavailable', async () => {
		connections.connections = [activeConnection];
		connections.activeId = 'c1';
		vi.mocked(api.getSchema).mockResolvedValue({ tables: [sampleTable] });
		vi.mocked(api.runQuery).mockReset();
		vi.mocked(api.runQuery)
			.mockRejectedValueOnce(
				`${COST_GUARD_PREFIX}EXPLAIN failed. Confirm to run this query anyway.`
			)
			.mockResolvedValueOnce({
				columns: [],
				rows: [],
				row_count: 0,
				truncated: false,
				warnings: []
			});

		render(BuilderPage);

		query.addTable(sampleTable);
		const user = userEvent.setup();
		await user.click(await screen.findByRole('button', { name: /Run Query/ }));

		const dialog = await screen.findByRole('alertdialog');
		expect(within(dialog).getByText('Safe DB could not preview this query')).toBeInTheDocument();
		expect(within(dialog).getByText(/read-only access, a row limit, and a timeout/)).toBeInTheDocument();
		expect(screen.queryByText(/EXPLAIN failed/)).not.toBeInTheDocument();

		await user.click(within(dialog).getByRole('button', { name: 'Run with safeguards' }));

		await waitFor(() => {
			expect(api.runQuery).toHaveBeenCalledTimes(2);
		});
		expect(vi.mocked(api.runQuery).mock.calls[1][2]).toBe(true);
	});

	it('shows high-cost copy when the estimate exceeds the threshold', async () => {
		connections.connections = [activeConnection];
		connections.activeId = 'c1';
		vi.mocked(api.getSchema).mockResolvedValue({ tables: [sampleTable] });
		vi.mocked(api.runQuery).mockReset();
		vi.mocked(api.runQuery).mockRejectedValue(
			`${COST_GUARD_PREFIX}Estimated query cost exceeds threshold. Confirm to run this query anyway.`
		);

		render(BuilderPage);

		query.addTable(sampleTable);
		const user = userEvent.setup();
		await user.click(await screen.findByRole('button', { name: /Run Query/ }));

		const dialog = await screen.findByRole('alertdialog');
		expect(
			within(dialog).getByText('This query may scan more data than expected')
		).toBeInTheDocument();
		expect(within(dialog).getByText(/estimated this query may be expensive/)).toBeInTheDocument();
		expect(screen.queryByText(/Estimated query cost exceeds threshold/)).not.toBeInTheDocument();

		await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
		expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
		expect(screen.queryByText(/Estimated query cost exceeds threshold/)).not.toBeInTheDocument();
	});

	it('Clear calls query.clear', async () => {
		connections.connections = [activeConnection];
		connections.activeId = 'c1';
		vi.mocked(api.getSchema).mockResolvedValue({ tables: [sampleTable] });

		render(BuilderPage);

		query.addTable(sampleTable);
		await waitFor(() => {
			expect(query.tables).toHaveLength(1);
		});

		const user = userEvent.setup();
		await user.click(screen.getByRole('button', { name: 'Clear' }));

		await waitFor(() => {
			expect(query.tables).toHaveLength(0);
		});
	});

	it('Save prompts and saves the spec with a generated id', async () => {
		connections.connections = [activeConnection];
		connections.activeId = 'c1';
		vi.mocked(api.getSchema).mockResolvedValue({ tables: [sampleTable] });
		vi.mocked(api.saveSavedQuery).mockResolvedValue();
		const uuidSpy = vi.spyOn(crypto, 'randomUUID').mockReturnValue('00000000-0000-4000-8000-000000000001');

		render(BuilderPage);

		query.addTable(sampleTable);
		await waitFor(() => {
			expect(query.tables).toHaveLength(1);
		});

		const user = userEvent.setup();
		await user.click(screen.getByRole('button', { name: 'Save' }));
		const dialog = screen.getByRole('dialog');
		await user.clear(within(dialog).getByPlaceholderText('Query name'));
		await user.type(within(dialog).getByPlaceholderText('Query name'), 'My saved query');
		await user.click(within(dialog).getByRole('button', { name: 'Save' }));

		await waitFor(() => {
			expect(api.saveSavedQuery).toHaveBeenCalled();
		});
		const savedArg = vi.mocked(api.saveSavedQuery).mock.calls[0][0];
		expect(savedArg.name).toBe('My saved query');
		expect(savedArg.connection_id).toBe('c1');
		expect(savedArg.id).toBe('00000000-0000-4000-8000-000000000001');
		expect(savedArg.spec.tables).toHaveLength(1);

		uuidSpy.mockRestore();
	});

	it('Save bails when the user cancels the prompt', async () => {
		connections.connections = [activeConnection];
		connections.activeId = 'c1';
		vi.mocked(api.getSchema).mockResolvedValue({ tables: [sampleTable] });
		vi.mocked(api.saveSavedQuery).mockResolvedValue();

		render(BuilderPage);

		query.addTable(sampleTable);
		await waitFor(() => {
			expect(query.tables).toHaveLength(1);
		});

		const user = userEvent.setup();
		await user.click(screen.getByRole('button', { name: 'Save' }));
		await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }));

		await new Promise((r) => setTimeout(r, 0));
		expect(api.saveSavedQuery).not.toHaveBeenCalled();
	});

	it('Save does not persist a whitespace-only name', async () => {
		connections.connections = [activeConnection];
		connections.activeId = 'c1';
		vi.mocked(api.getSchema).mockResolvedValue({ tables: [sampleTable] });
		vi.mocked(api.saveSavedQuery).mockResolvedValue();

		render(BuilderPage);

		query.addTable(sampleTable);
		await waitFor(() => {
			expect(query.tables).toHaveLength(1);
		});

		const user = userEvent.setup();
		await user.click(screen.getByRole('button', { name: 'Save' }));
		const dialog = screen.getByRole('dialog');
		await user.clear(within(dialog).getByPlaceholderText('Query name'));
		await user.type(within(dialog).getByPlaceholderText('Query name'), '   ');
		await user.click(within(dialog).getByRole('button', { name: 'Save' }));

		await new Promise((r) => setTimeout(r, 0));
		expect(api.saveSavedQuery).not.toHaveBeenCalled();
	});

	it('Limit input forwards parsed values to query.setLimit (clamped to [1, MAX_LIMIT])', async () => {
		connections.connections = [activeConnection];
		connections.activeId = 'c1';
		vi.mocked(api.getSchema).mockResolvedValue({ tables: [sampleTable] });

		render(BuilderPage);

		query.addTable(sampleTable);
		const limit = (await screen.findByLabelText('Limit')) as HTMLInputElement;
		expect(limit).toBeInTheDocument();

		function setLimitValue(value: string) {
			limit.value = value;
			fireEvent.input(limit);
		}

		setLimitValue('25');
		expect(query.limit).toBe(25);

		setLimitValue('9999');
		expect(query.limit).toBe(1000);

		setLimitValue('0');
		expect(query.limit).toBe(1);

		setLimitValue('');
		expect(query.limit).toBe(1);
	});
});
