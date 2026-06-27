import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import FilterRow from '$lib/components/FilterRow.svelte';
import FilterBuilder from '$lib/components/FilterBuilder.svelte';
import { query } from '$lib/stores/query.svelte';
import type { CanvasTable } from '$lib/stores/query.svelte';
import type { FilterSpec, TableInfo } from '$lib/ir';

const usersTable: TableInfo = {
	schema: 'public',
	name: 'users',
	columns: [
		{ name: 'id', data_type: 'int', nullable: false, is_indexed: true },
		{ name: 'active', data_type: 'bool', nullable: false, is_indexed: false },
		{ name: 'email', data_type: 'varchar', nullable: true, is_indexed: false },
		{ name: 'created_at', data_type: 'date', nullable: true, is_indexed: false },
		{ name: 'updated_at', data_type: 'timestamp', nullable: true, is_indexed: false }
	],
	indexes: []
};

const ordersTable: TableInfo = {
	schema: 'public',
	name: 'orders',
	columns: [{ name: 'id', data_type: 'int', nullable: false, is_indexed: true }],
	indexes: []
};

function canvasTable(info: TableInfo, alias: string): CanvasTable {
	return { alias, tableInfo: info, x: 0, y: 0 };
}

function makeFilter(overrides: Partial<FilterSpec> = {}): FilterSpec {
	return {
		id: 'f1',
		table_alias: 't0',
		column: 'id',
		op: 'Eq',
		value: { Single: { kind: 'Int', text: '1' } },
		...overrides
	};
}

describe('FilterRow', () => {
	beforeEach(() => {
		query.clear();
	});

	afterEach(() => {
		cleanup();
		query.clear();
	});

	// These tests use `render(FilterBuilder, ...)` (not FilterRow directly) so
	// the row re-renders when the query store updates. FilterRow is a presentational
	// component that only re-derives when its `filter` prop changes, so a
	// parent that reads from the store is required to observe the effects of
	// store mutations.

	it('changeTable swaps the column to the new table\'s first column and reuses the op if compatible', async () => {
		const user = userEvent.setup();
		query.addTable(usersTable);
		query.addTable(ordersTable);
		query.addFilter(makeFilter());

		render(FilterBuilder);

		const tableSelect = screen.getByLabelText('Filter table') as HTMLSelectElement;
		await user.selectOptions(tableSelect, 't1');

		const child = query.filters.children[0];
		if ('Leaf' in child) {
			expect(child.Leaf.table_alias).toBe('t1');
			expect(child.Leaf.column).toBe('id');
			expect(child.Leaf.op).toBe('Eq');
		} else {
			throw new Error('expected Leaf');
		}
	});

	it('changeTable picks a new op when the current op is not in the new column\'s ops list', async () => {
		const user = userEvent.setup();
		query.addTable(usersTable);
		query.addTable(ordersTable);
		query.addFilter(
			makeFilter({
				column: 'email',
				op: 'Like',
				value: { Single: { kind: 'Text', text: 'a%' } }
			})
		);

		render(FilterBuilder);

		const tableSelect = screen.getByLabelText('Filter table') as HTMLSelectElement;
		await user.selectOptions(tableSelect, 't1');

		const child = query.filters.children[0];
		if ('Leaf' in child) {
			expect(child.Leaf.table_alias).toBe('t1');
			expect(child.Leaf.column).toBe('id');
			// Eq is the first op for int columns.
			expect(child.Leaf.op).toBe('Eq');
		} else {
			throw new Error('expected Leaf');
		}
	});

	it('changeColumn updates the column and reuses the op if compatible', async () => {
		const user = userEvent.setup();
		query.addTable(usersTable);
		query.addFilter(makeFilter());

		render(FilterBuilder);

		const colSelect = screen.getByLabelText('Filter column') as HTMLSelectElement;
		await user.selectOptions(colSelect, 'active');

		const child = query.filters.children[0];
		if ('Leaf' in child) {
			expect(child.Leaf.column).toBe('active');
			expect(child.Leaf.op).toBe('Eq');
		} else {
			throw new Error('expected Leaf');
		}
	});

	it('changeOp to Between swaps to from/to inputs and value becomes a Pair of two Int literals', async () => {
		const user = userEvent.setup();
		query.addTable(usersTable);
		query.addFilter(makeFilter({ column: 'id', op: 'Eq' }));

		render(FilterBuilder);

		const opSelect = screen.getByLabelText('Filter operator') as HTMLSelectElement;
		await user.selectOptions(opSelect, 'Between');

		await waitFor(() => {
			const child = query.filters.children[0];
			if ('Leaf' in child) {
				expect(child.Leaf.op).toBe('Between');
				expect(child.Leaf.value).toEqual({
					Pair: [
						{ kind: 'Int', text: '' },
						{ kind: 'Int', text: '' }
					]
				});
			}
		});
	});

	it('changeOp to In keeps at least one input; Add value appends; Remove value drops the last (no-op when length is 1)', async () => {
		const user = userEvent.setup();
		query.addTable(usersTable);
		query.addFilter(
			makeFilter({
				column: 'email',
				op: 'In',
				value: { List: [{ kind: 'Text', text: 'a@b.c' }] }
			})
		);

		render(FilterBuilder);

		const inputsBefore = screen.getAllByPlaceholderText('value');
		expect(inputsBefore).toHaveLength(1);

		await user.click(screen.getByRole('button', { name: 'Add value' }));
		await waitFor(() => {
			expect(screen.getAllByPlaceholderText('value')).toHaveLength(2);
		});

		await waitFor(() => {
			expect(screen.getAllByRole('button', { name: 'Remove value' })).toHaveLength(2);
		});

		const removeButtons = screen.getAllByRole('button', { name: 'Remove value' });
		await user.click(removeButtons[1]);
		await waitFor(() => {
			expect(screen.getAllByPlaceholderText('value')).toHaveLength(1);
		});

		await waitFor(() => {
			expect(screen.queryByRole('button', { name: 'Remove value' })).not.toBeInTheDocument();
		});
	});

	it('changeOp to IsNull removes the value input and the leaf value becomes null', async () => {
		const user = userEvent.setup();
		query.addTable(usersTable);
		query.addFilter(makeFilter({ column: 'email', op: 'Eq' }));

		render(FilterBuilder);

		const opSelect = screen.getByLabelText('Filter operator') as HTMLSelectElement;
		await user.selectOptions(opSelect, 'IsNull');

		await waitFor(() => {
			const child = query.filters.children[0];
			if ('Leaf' in child) {
				expect(child.Leaf.op).toBe('IsNull');
				expect(child.Leaf.value).toBeNull();
			} else {
				throw new Error('expected Leaf');
			}
		});
		// No value input is rendered for valueKind === 'None'.
		await waitFor(() => {
			expect(screen.queryByPlaceholderText('value')).not.toBeInTheDocument();
		});
		expect(screen.queryByPlaceholderText('from')).not.toBeInTheDocument();
	});

	it('Bool column shows a true/false select instead of a text input', () => {
		query.addTable(usersTable);
		query.addFilter(makeFilter({ column: 'active', op: 'Eq' }));

		render(FilterBuilder);

		expect(screen.getByRole('option', { name: 'true' })).toBeInTheDocument();
		expect(screen.getByRole('option', { name: 'false' })).toBeInTheDocument();
	});

	it('Date column shows type=date and DateTime shows type=datetime-local', async () => {
		query.addTable(usersTable);
		query.addFilter(makeFilter({ column: 'created_at', op: 'Eq' }));

		render(FilterBuilder);

		const dateInput = screen.getByPlaceholderText('value') as HTMLInputElement;
		expect(dateInput.type).toBe('date');

		// Switch to a DateTime column.
		query.updateFilter([0], makeFilter({ column: 'updated_at', op: 'Eq' }));
		await waitFor(() => {
			const all = screen.getAllByPlaceholderText('value') as HTMLInputElement[];
			expect(all[0].type).toBe('datetime-local');
		});
	});

	it('per-row remove (×) calls removeFilterNode', () => {
		query.addTable(usersTable);
		query.addFilter(makeFilter());

		render(FilterBuilder);

		fireEvent.click(screen.getByRole('button', { name: 'Remove filter' }));
		expect(query.filters.children).toHaveLength(0);
	});

	it('typing into the value input updates the leaf Single.text', async () => {
		const user = userEvent.setup();
		query.addTable(usersTable);
		query.addFilter(makeFilter({ column: 'email', op: 'Eq' }));

		render(FilterBuilder);

		const input = screen.getByPlaceholderText('value') as HTMLInputElement;
		await user.clear(input);
		await user.type(input, 'hello');

		const child = query.filters.children[0];
		if ('Leaf' in child && child.Leaf.value && 'Single' in child.Leaf.value) {
			expect(child.Leaf.value.Single.text).toBe('hello');
		} else {
			throw new Error('expected Single value');
		}
	});
});
