import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import FilterBuilder from '$lib/components/FilterBuilder.svelte';
import { query } from '$lib/stores/query.svelte';
import type { TableInfo } from '$lib/ir';

const intTable: TableInfo = {
	schema: 'public',
	name: 'items',
	columns: [
		{ name: 'id', data_type: 'int', nullable: false, is_indexed: true },
		{ name: 'qty', data_type: 'int', nullable: true, is_indexed: false }
	],
	indexes: []
};

function currentLeaf() {
	const child = query.filters.children[0];
	if (!child || !('Leaf' in child)) throw new Error('expected a leaf filter');
	return child.Leaf;
}

describe('FilterBuilder group controls', () => {
	afterEach(() => {
		cleanup();
		query.clear();
	});

	it('shows a placeholder when the root group is empty', () => {
		render(FilterBuilder);
		expect(screen.getByText('No conditions')).toBeInTheDocument();
	});

	it('adds a leaf filter via the Filter button', async () => {
		query.addTable(intTable);
		const user = userEvent.setup();
		render(FilterBuilder);

		await user.click(screen.getByRole('button', { name: 'Filter' }));

		expect(query.filters.children).toHaveLength(1);
		expect(screen.getByRole('button', { name: 'Remove filter' })).toBeInTheDocument();
	});

	it('adds a nested group via the Group button', async () => {
		query.addTable(intTable);
		const user = userEvent.setup();
		render(FilterBuilder);

		await user.click(screen.getByRole('button', { name: 'Group' }));

		expect(query.filters.children).toHaveLength(1);
		expect(screen.getByRole('button', { name: 'Remove group' })).toBeInTheDocument();
	});

	it('toggles the root connector between AND and OR', async () => {
		const user = userEvent.setup();
		render(FilterBuilder);

		expect(screen.getByText('AND')).toBeInTheDocument();
		await user.click(screen.getByText('AND'));

		expect(screen.getByText('OR')).toBeInTheDocument();
		expect(query.filters.connector).toBe('Or');
	});
});

describe('FilterBuilder per-condition connector', () => {
	afterEach(() => {
		cleanup();
		query.clear();
	});

	it('shows a per-line connector pill for every condition after the first', async () => {
		query.addTable(intTable);
		const alias = query.tables[0].alias;
		query.addFilter({
			table_alias: alias,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '1' } }
		});
		query.addFilter({
			table_alias: alias,
			column: 'qty',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '2' } }
		});

		const user = userEvent.setup();
		render(FilterBuilder);

		const pills = screen.getAllByRole('button', { name: /Toggle connector/ });
		expect(pills).toHaveLength(1);
		expect(pills[0]).toHaveTextContent('AND');

		await user.click(pills[0]);
		expect(screen.getByRole('button', { name: /Toggle connector/ })).toHaveTextContent('OR');
		expect(query.getConnectorForChild([1])).toBe('Or');
	});

	it('uppercases the per-line connector pill text', () => {
		query.addTable(intTable);
		const alias = query.tables[0].alias;
		query.addFilter({
			table_alias: alias,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '1' } }
		});
		query.addFilter({
			table_alias: alias,
			column: 'qty',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '2' } }
		});

		render(FilterBuilder);
		expect(screen.getByRole('button', { name: /Toggle connector/ })).toHaveTextContent('AND');
	});

	it('falls back to the group connector when no override is set', () => {
		query.addTable(intTable);
		const alias = query.tables[0].alias;
		query.addFilter({
			table_alias: alias,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '1' } }
		});
		query.setGroupConnector([], 'Or');
		expect(query.getConnectorForChild([0])).toBe('Or');
	});

	it('toggling a child connector twice restores the group default', () => {
		query.addTable(intTable);
		const alias = query.tables[0].alias;
		query.addFilter({
			table_alias: alias,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '1' } }
		});
		query.addFilter({
			table_alias: alias,
			column: 'qty',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '2' } }
		});
		query.toggleChildConnector([1]);
		expect(query.getConnectorForChild([1])).toBe('Or');
		query.toggleChildConnector([1]);
		expect(query.getConnectorForChild([1])).toBe('And');
	});
});

describe('FilterRow value editing', () => {
	beforeEach(() => {
		query.addTable(intTable);
		query.addFilter({
			table_alias: query.tables[0].alias,
			column: 'id',
			op: 'Eq',
			value: { Single: { kind: 'Int', text: '' } }
		});
	});

	afterEach(() => {
		cleanup();
		query.clear();
	});

	it('edits a single value and updates the store', async () => {
		const user = userEvent.setup();
		render(FilterBuilder);

		const input = screen.getByPlaceholderText('value');
		await user.type(input, '42');

		expect(currentLeaf().value).toEqual({ Single: { kind: 'Int', text: '42' } });
	});

	it('switches to a pair (Between) with from/to inputs', async () => {
		const user = userEvent.setup();
		render(FilterBuilder);

		await user.selectOptions(screen.getByLabelText('Filter operator'), 'Between');

		expect(screen.getByPlaceholderText('from')).toBeInTheDocument();
		expect(screen.getByPlaceholderText('to')).toBeInTheDocument();
		expect(currentLeaf().value).toEqual({
			Pair: [
				{ kind: 'Int', text: '' },
				{ kind: 'Int', text: '' }
			]
		});
	});

	it('switches to a list (In) and supports add/remove of items', async () => {
		const user = userEvent.setup();
		render(FilterBuilder);

		await user.selectOptions(screen.getByLabelText('Filter operator'), 'In');

		expect(screen.getAllByPlaceholderText('value')).toHaveLength(1);

		await user.click(screen.getByRole('button', { name: 'Add value' }));
		expect(screen.getAllByPlaceholderText('value')).toHaveLength(2);
		expect(currentLeaf().value).toEqual({
			List: [
				{ kind: 'Int', text: '' },
				{ kind: 'Int', text: '' }
			]
		});

		await user.click(screen.getAllByRole('button', { name: 'Remove value' })[0]);
		expect(screen.getAllByPlaceholderText('value')).toHaveLength(1);
	});

	it('switches to a no-value operator (IsNull)', async () => {
		const user = userEvent.setup();
		render(FilterBuilder);

		await user.selectOptions(screen.getByLabelText('Filter operator'), 'IsNull');

		expect(screen.queryByPlaceholderText('value')).not.toBeInTheDocument();
		expect(currentLeaf().value).toBeNull();
	});
});
