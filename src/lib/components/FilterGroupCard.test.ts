import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import FilterGroupCard from '$lib/components/FilterGroupCard.svelte';
import { query } from '$lib/stores/query.svelte';
import type { CanvasTable } from '$lib/stores/query.svelte';
import type { FilterGroup, TableInfo } from '$lib/ir';

const usersTable: TableInfo = {
	schema: 'public',
	name: 'users',
	columns: [
		{ name: 'id', data_type: 'int', nullable: false, is_indexed: true },
		{ name: 'email', data_type: 'varchar', nullable: true, is_indexed: false }
	],
	indexes: []
};

function canvas(): CanvasTable {
	return { alias: 't0', tableInfo: usersTable, x: 40, y: 40 };
}

function makeGroup(children: FilterGroup['children'] = [], connector: 'And' | 'Or' = 'And'): FilterGroup {
	return { id: 'g', connector, children };
}

describe('FilterGroupCard', () => {
	beforeEach(() => {
		query.clear();
	});

	afterEach(() => {
		cleanup();
		query.clear();
	});

	it('shows the "No conditions" placeholder when the group has no children', () => {
		const group = makeGroup();
		render(FilterGroupCard, { group, path: [], tables: [canvas()] });
		expect(screen.getByText('No conditions')).toBeInTheDocument();
	});

	it('does not render a per-line connector pill for the first child', () => {
		const group = makeGroup();
		render(FilterGroupCard, { group, path: [], tables: [canvas()] });
		expect(screen.queryByRole('button', { name: /Toggle connector/ })).not.toBeInTheDocument();
	});

	it('renders a per-line connector pill for every child after the first', () => {
		const group = makeGroup([
			{ Leaf: { id: 'l1', table_alias: 't0', column: 'id', op: 'Eq', value: null } },
			{ Leaf: { id: 'l2', table_alias: 't0', column: 'id', op: 'Eq', value: null } },
			{ Leaf: { id: 'l3', table_alias: 't0', column: 'id', op: 'Eq', value: null } }
		]);
		render(FilterGroupCard, { group, path: [], tables: [canvas()] });

		// 2 children after the first → 2 connector pills.
		const pills = screen.getAllByRole('button', { name: /Toggle connector/ });
		expect(pills).toHaveLength(2);
	});

	it('at depth 0, no remove-group button is shown', () => {
		const group = makeGroup();
		render(FilterGroupCard, { group, path: [], tables: [canvas()], depth: 0 });
		expect(screen.queryByRole('button', { name: 'Remove group' })).not.toBeInTheDocument();
	});

	it('at depth > 0, the remove-group button is rendered and calls removeFilterNode', () => {
		const group = makeGroup();
		render(FilterGroupCard, { group, path: [0], tables: [canvas()], depth: 1 });
		const remove = screen.getByRole('button', { name: 'Remove group' });
		expect(remove).toBeInTheDocument();

		// Use a real store with a nested group at path [0] so removeFilterNode has
		// something to remove.
		query.clear();
		query.addTable(usersTable);
		query.addGroupToGroup([], 'And'); // root.children = [Group]
		const before = query.filters.children.length;
		expect(before).toBe(1);

		fireEvent.click(remove);
		expect(query.filters.children.length).toBe(0);
	});

	it('Filter button calls addFilterToGroup with the first table\'s first column', () => {
		const group = makeGroup();
		query.addTable(usersTable);
		render(FilterGroupCard, { group, path: [], tables: [canvas()] });

		fireEvent.click(screen.getByRole('button', { name: 'Filter' }));

		expect(query.filters.children).toHaveLength(1);
		const leaf = query.filters.children[0];
		expect('Leaf' in leaf).toBe(true);
		if ('Leaf' in leaf) {
			expect(leaf.Leaf.table_alias).toBe('t0');
			expect(leaf.Leaf.column).toBe('id');
			expect(leaf.Leaf.op).toBe('Eq');
		}
	});

	it('Filter button is a no-op when the first table has no columns', () => {
		const empty: TableInfo = { schema: 'public', name: 'empty', columns: [], indexes: [] };
		const group = makeGroup();
		render(FilterGroupCard, { group, path: [], tables: [{ alias: 't0', tableInfo: empty, x: 0, y: 0 }] });

		fireEvent.click(screen.getByRole('button', { name: 'Filter' }));

		expect(query.filters.children).toHaveLength(0);
	});

	it('Group button adds a nested group with connector And', () => {
		const group = makeGroup();
		query.addTable(usersTable);
		render(FilterGroupCard, { group, path: [], tables: [canvas()] });

		fireEvent.click(screen.getByRole('button', { name: 'Group' }));

		expect(query.filters.children).toHaveLength(1);
		const child = query.filters.children[0];
		expect('Group' in child).toBe(true);
		if ('Group' in child) {
			expect(child.Group.connector).toBe('And');
			expect(child.Group.children).toHaveLength(0);
		}
	});

	it('addFilter/addGroup target the correct path (nested path [0])', () => {
		query.addTable(usersTable);
		query.addGroupToGroup([], 'And'); // root.children = [Group]
		const nestedPath = [0];
		const nestedGroup = (query.filters.children[0] as { Group: FilterGroup }).Group;

		render(FilterGroupCard, { group: nestedGroup, path: nestedPath, tables: [canvas()], depth: 1 });

		fireEvent.click(screen.getByRole('button', { name: 'Filter' }));

		const after = (query.filters.children[0] as { Group: FilterGroup }).Group;
		expect(after.children).toHaveLength(1);
	});

	it('per-child connector pill toggles via toggleChildConnector', async () => {
		// The component reads the connector from the query store's
		// connectorOverrides map, not from the local `group` prop. Use the
		// store's group so the override path actually resolves.
		query.addTable(usersTable);
		query.addFilter({ table_alias: 't0', column: 'id', op: 'Eq', value: { Single: { kind: 'Int', text: '1' } } });
		query.addFilter({ table_alias: 't0', column: 'id', op: 'Eq', value: { Single: { kind: 'Int', text: '2' } } });

		const { container } = render(FilterGroupCard, {
			group: query.filters,
			path: [],
			tables: [canvas()]
		});

		const pill = container.querySelector('button[aria-label^="Toggle connector"]') as HTMLButtonElement;
		expect(pill).toBeTruthy();
		expect(pill.textContent?.trim()).toBe('AND');

		fireEvent.click(pill);

		await waitFor(() => {
			expect(pill.textContent?.trim()).toBe('OR');
		});
	});
});
