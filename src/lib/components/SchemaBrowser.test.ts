import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import SchemaBrowser from '$lib/components/SchemaBrowser.svelte';
import { schema } from '$lib/stores/schema.svelte';
import type { TableInfo } from '$lib/ir';

const usersTable: TableInfo = {
	schema: 'public',
	name: 'users',
	columns: [
		{ name: 'id', data_type: 'int', nullable: false, is_indexed: true },
		{ name: 'email', data_type: 'varchar', nullable: true, is_indexed: false },
		{ name: 'deleted_at', data_type: 'timestamp', nullable: true, is_indexed: false }
	],
	indexes: [{ name: 'users_pkey', columns: ['id'], is_unique: true, is_primary: true }]
};

const ordersTable: TableInfo = {
	schema: 'public',
	name: 'orders',
	columns: [
		{ name: 'id', data_type: 'int', nullable: false, is_indexed: true },
		{ name: 'user_id', data_type: 'int', nullable: false, is_indexed: true }
	],
	indexes: [
		{ name: 'orders_pkey', columns: ['id'], is_unique: true, is_primary: true },
		{ name: 'orders_user_id_idx', columns: ['user_id'], is_unique: false, is_primary: false }
	]
};

function setSchemaState(opts: { tables?: TableInfo[]; loading?: boolean; error?: string | null } = {}) {
	schema.schema = opts.tables ? { tables: opts.tables } : schema.schema;
	schema.loading = opts.loading ?? false;
	schema.error = opts.error ?? null;
	if (opts.tables) {
		schema.loadedConnectionId = 'c1';
	} else {
		schema.loadedConnectionId = null;
	}
	schema.search = '';
}

describe('SchemaBrowser', () => {
	beforeEach(() => {
		schema.clear();
	});

	afterEach(() => {
		cleanup();
		schema.clear();
	});

	it('renders the loading spinner while schema.loading is true', () => {
		setSchemaState({ loading: true });
		render(SchemaBrowser);
		expect(screen.getByText('Loading schema…')).toBeInTheDocument();
	});

	it('renders the error banner with the schema error message', () => {
		setSchemaState({ error: 'connection timed out' });
		render(SchemaBrowser);
		expect(screen.getByText('Failed to load schema')).toBeInTheDocument();
		expect(screen.getByText('connection timed out')).toBeInTheDocument();
	});

	it('shows the keyring hint when the error mentions Open Connections', () => {
		setSchemaState({ error: 'No password stored. Open Connections to re-save.' });
		render(SchemaBrowser);
		expect(
			screen.getByText(/Credentials may need to be re-saved after a keyring backend change/)
		).toBeInTheDocument();
	});

	it('shows the keyring hint when the error mentions save the connection', () => {
		setSchemaState({ error: 'Please save the connection again.' });
		render(SchemaBrowser);
		expect(
			screen.getByText(/Credentials may need to be re-saved after a keyring backend change/)
		).toBeInTheDocument();
	});

	it('shows "No tables found." when the schema has no tables', () => {
		setSchemaState({ tables: [] });
		render(SchemaBrowser);
		expect(screen.getByText('No tables found.')).toBeInTheDocument();
	});

	it('shows "No tables match your search." when search filters out all tables', () => {
		setSchemaState({ tables: [usersTable] });
		schema.search = 'nonexistent';
		render(SchemaBrowser);
		expect(screen.getByText('No tables match your search.')).toBeInTheDocument();
	});

	it('renders a row per table with the column count badge', () => {
		setSchemaState({ tables: [usersTable, ordersTable] });
		render(SchemaBrowser);

		const buttons = screen.getAllByRole('button', { name: /users/ });
		expect(buttons.length).toBeGreaterThan(0);
		// The row buttons should mention the column count somewhere
		expect(screen.getByText('3')).toBeInTheDocument();
		expect(screen.getByText('2')).toBeInTheDocument();
	});

	it('+ button is not rendered when onAddTable is not provided', () => {
		setSchemaState({ tables: [usersTable] });
		render(SchemaBrowser);
		expect(screen.queryByTitle('Add to canvas')).not.toBeInTheDocument();
	});

	it('+ button is rendered when onAddTable is provided and invokes the callback with the table', async () => {
		setSchemaState({ tables: [usersTable] });
		const onAddTable = vi.fn();
		render(SchemaBrowser, { onAddTable });

		const addButton = screen.getByTitle('Add to canvas');
		fireEvent.click(addButton);

		expect(onAddTable).toHaveBeenCalledWith(usersTable);
	});

	it('clicking the row toggles the column list (expands and collapses)', async () => {
		setSchemaState({ tables: [usersTable] });
		render(SchemaBrowser);

		// Initially columns are hidden.
		expect(screen.queryByText('email')).not.toBeInTheDocument();

		// Click the row to expand.
		const rowButton = screen.getByRole('button', { name: /users/ });
		fireEvent.click(rowButton);

		expect(await screen.findByText('email')).toBeInTheDocument();
		expect(screen.getByText('deleted_at')).toBeInTheDocument();
		expect(screen.getByText('varchar')).toBeInTheDocument();
		expect(screen.getByText('timestamp')).toBeInTheDocument();

		// Click again to collapse.
		fireEvent.click(rowButton);
		await waitFor(() => {
			expect(screen.queryByText('email')).not.toBeInTheDocument();
		});
	});

	it('expanded rows show indexed / nullable / PK / IDX badges', async () => {
		setSchemaState({ tables: [usersTable, ordersTable] });
		render(SchemaBrowser);

		// Expand users
		fireEvent.click(screen.getByRole('button', { name: /users/ }));
		expect(await screen.findByText('indexed')).toBeInTheDocument();
		// email and deleted_at are both nullable, so the "null" badge appears twice.
		expect(screen.getAllByText('null').length).toBeGreaterThanOrEqual(1);
		expect(screen.getByText('PK')).toBeInTheDocument();
		expect(screen.getByText('users_pkey')).toBeInTheDocument();

		// Expand orders and verify the non-unique index gets the IDX pill.
		fireEvent.click(screen.getByRole('button', { name: /orders/ }));
		expect(await screen.findByText('IDX')).toBeInTheDocument();
		expect(screen.getByText('orders_user_id_idx')).toBeInTheDocument();
	});

	it('search input is bound to schema.search and filters tables', async () => {
		setSchemaState({ tables: [usersTable, ordersTable] });
		const user = userEvent.setup();
		render(SchemaBrowser);

		const search = screen.getByPlaceholderText('Search tables…') as HTMLInputElement;
		await user.type(search, 'ord');

		await waitFor(() => {
			expect(screen.queryByRole('button', { name: /users/ })).not.toBeInTheDocument();
		});
		expect(screen.getByRole('button', { name: /orders/ })).toBeInTheDocument();
	});
});
