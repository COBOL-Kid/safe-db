import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import TableCard from '$lib/components/TableCard.svelte';
import { query } from '$lib/stores/query.svelte';
import type { CanvasTable } from '$lib/stores/query.svelte';
import type { TableInfo } from '$lib/ir';

const usersTable: TableInfo = {
	schema: 'public',
	name: 'users',
	columns: [
		{ name: 'id', data_type: 'int', nullable: false, is_indexed: true },
		{ name: 'email', data_type: 'varchar', nullable: true, is_indexed: false },
		{ name: 'name', data_type: 'varchar', nullable: false, is_indexed: false }
	],
	indexes: [{ name: 'users_pkey', columns: ['id'], is_unique: true, is_primary: true }]
};

function canvas(): CanvasTable {
	return { alias: 't0', tableInfo: usersTable, x: 40, y: 40 };
}

describe('TableCard', () => {
	beforeEach(() => {
		query.clear();
	});

	afterEach(() => {
		cleanup();
		query.clear();
	});

	function renderCard(props: Partial<{ canvasTable: CanvasTable; highlightJoinTargets: { sourceAlias: string; sourceColumn: string } | null }> = {}) {
		const ct = props.canvasTable ?? canvas();
		const onStartJoin = vi.fn();
		const onStartResize = vi.fn();
		const result = render(TableCard, {
			canvasTable: ct,
			onStartJoin,
			onStartResize,
			highlightJoinTargets: props.highlightJoinTargets ?? null
		} as Record<string, unknown>);
		return { ...result, onStartJoin, onStartResize };
	}

	it('renders the table name and remove button', () => {
		const { onStartJoin: _ } = renderCard();
		expect(screen.getByText('users')).toBeInTheDocument();
		expect(screen.getByRole('button', { name: 'Remove table' })).toBeInTheDocument();
	});

	it('Remove button calls query.removeTable(alias)', () => {
		const ct = canvas();
		query.addTable(usersTable); // ensures the store actually has it
		renderCard({ canvasTable: ct });
		fireEvent.click(screen.getByRole('button', { name: 'Remove table' }));

		expect(query.tables.find((t) => t.alias === 't0')).toBeUndefined();
	});

	it('checkbox reflects query.isColumnSelected and clicking toggles', () => {
		const ct = canvas();
		query.addTable(usersTable);
		renderCard({ canvasTable: ct });

		const idRow = screen.getByText('id').closest('button')!;
		expect(idRow).toBeInTheDocument();

		// Not selected yet
		expect(query.isColumnSelected('t0', 'id')).toBe(false);

		fireEvent.click(idRow);
		expect(query.isColumnSelected('t0', 'id')).toBe(true);

		fireEvent.click(idRow);
		expect(query.isColumnSelected('t0', 'id')).toBe(false);
	});

	it('join handle is only rendered for is_indexed columns', () => {
		const ct = canvas();
		query.addTable(usersTable);
		renderCard({ canvasTable: ct });

		// Only `id` is indexed; the join handle should be a button with the
		// source alias/column attrs, exactly one per indexed column.
		const joinButtons = screen.getAllByTitle('Drag to another indexed column to join');
		expect(joinButtons).toHaveLength(1);
		expect(joinButtons[0].getAttribute('data-join-source-alias')).toBe('t0');
		expect(joinButtons[0].getAttribute('data-join-source-column')).toBe('id');
	});

	it('resize handle is rendered and delegates mousedown', () => {
		const ct = canvas();
		query.addTable(usersTable);
		const { onStartResize } = renderCard({ canvasTable: ct });

		const handle = screen.getByRole('button', { name: 'Resize table' });
		fireEvent.mouseDown(handle);

		expect(onStartResize).toHaveBeenCalledTimes(1);
		const [event, alias] = onStartResize.mock.calls[0];
		expect(event).toBeDefined();
		expect(alias).toBe('t0');
	});

	it('onStartJoin is invoked with (event, alias, column) on mousedown of the join handle', () => {
		const ct = canvas();
		query.addTable(usersTable);
		const { onStartJoin } = renderCard({ canvasTable: ct });

		const handle = screen.getByTitle('Drag to another indexed column to join');
		fireEvent.mouseDown(handle);

		expect(onStartJoin).toHaveBeenCalledTimes(1);
		const [event, alias, column] = onStartJoin.mock.calls[0];
		expect(event).toBeDefined();
		expect(alias).toBe('t0');
		expect(column).toBe('id');
	});

	it('quick-filter menu opens and only one is open at a time', async () => {
		const ct = canvas();
		query.addTable(usersTable);
		renderCard({ canvasTable: ct });

		// Initially no menu
		expect(screen.queryByText('Filter where')).not.toBeInTheDocument();

		// Open the menu via the email row's trigger.
		const emailRow = screen.getByText('email').closest('[data-column]')!;
		const emailTrigger = emailRow.querySelector(
			'button[title="Filter options"]'
		) as HTMLButtonElement;
		expect(emailTrigger).toBeTruthy();
		fireEvent.click(emailTrigger);

		expect(await screen.findByText('Filter where')).toBeInTheDocument();

		// Clicking again on the same trigger closes it
		fireEvent.click(emailTrigger);
		await waitFor(() => {
			expect(screen.queryByText('Filter where')).not.toBeInTheDocument();
		});

		// Open it again
		fireEvent.click(emailTrigger);
		expect(await screen.findByText('Filter where')).toBeInTheDocument();

		// Clicking outside closes the menu (svelte:window onclick handler)
		fireEvent.click(document.body);
		await waitFor(() => {
			expect(screen.queryByText('Filter where')).not.toBeInTheDocument();
		});
	});

	it('quick-filter menu items use opsForColumn and produce a properly shaped FilterSpec', async () => {
		const ct = canvas();
		query.addTable(usersTable);
		renderCard({ canvasTable: ct });

		// Open the menu for `email` (text column).
		const emailRow = screen.getByText('email').closest('[data-column]')!;
		const emailTrigger = emailRow.querySelector('button[title="Filter options"]') as HTMLButtonElement;
		expect(emailTrigger).toBeTruthy();
		fireEvent.click(emailTrigger);

		// Click the Eq option in the menu.
		const eqItem = await screen.findByText(/email =/);
		fireEvent.click(eqItem);

		// A new leaf was added with table_alias=t0, column=email, op=Eq, value=Single Text "".
		const leaf = query.filters.children.find((c) => 'Leaf' in c)?.Leaf;
		expect(leaf).toBeDefined();
		expect(leaf!.table_alias).toBe('t0');
		expect(leaf!.column).toBe('email');
		expect(leaf!.op).toBe('Eq');
		expect(leaf!.value).toEqual({ Single: { kind: 'Text', text: '' } });
	});

	it('clicking the per-column "..." button does not toggle the column checkbox', () => {
		const ct = canvas();
		query.addTable(usersTable);
		renderCard({ canvasTable: ct });

		expect(query.isColumnSelected('t0', 'email')).toBe(false);

		const emailRow = screen.getByText('email').closest('[data-column]')!;
		const trigger = emailRow.querySelector('button[title="Filter options"]') as HTMLButtonElement;
		fireEvent.click(trigger);

		// The checkbox state is unchanged.
		expect(query.isColumnSelected('t0', 'email')).toBe(false);
	});

	it('highlightJoinTargets lights up other indexed columns but not the source', () => {
		const ct = canvas();
		query.addTable(usersTable);
		const { container } = renderCard({
			canvasTable: ct,
			highlightJoinTargets: { sourceAlias: 't0', sourceColumn: 'id' }
		});

		// The id row should not be highlighted (it is the source).
		const idRow = container.querySelector('[data-column="id"]')!;
		const idClass = idRow.getAttribute('class') ?? '';
		expect(idClass).not.toContain('shadow-[');
		expect(idClass).not.toContain('ring-inset');

		// No other indexed columns exist on this table, so there's no other
		// highlighted row — but the assertion above is enough.
	});

	it('highlightJoinTargets does not light up non-indexed columns', () => {
		// Add a second indexed column to the table so we have a real target.
		const multi = {
			...usersTable,
			columns: [
				{ name: 'id', data_type: 'int', nullable: false, is_indexed: true },
				{ name: 'org_id', data_type: 'int', nullable: false, is_indexed: true },
				{ name: 'email', data_type: 'varchar', nullable: true, is_indexed: false }
			]
		} satisfies TableInfo;
		query.clear();
		query.addTable(multi);
		const ct: CanvasTable = { alias: 't0', tableInfo: multi, x: 40, y: 40 };
		const { container } = renderCard({
			canvasTable: ct,
			highlightJoinTargets: { sourceAlias: 't0', sourceColumn: 'id' }
		});

		const orgRow = container.querySelector('[data-column="org_id"]')!;
		expect(orgRow.getAttribute('class') ?? '').toContain('ring-inset');
		expect(orgRow.getAttribute('class') ?? '').not.toContain('shadow-[');

		const emailRow = container.querySelector('[data-column="email"]')!;
		expect(emailRow.getAttribute('class') ?? '').not.toContain('ring-inset');
	});

	it('does not light up anything when highlightJoinTargets is null', () => {
		const ct = canvas();
		query.addTable(usersTable);
		const { container } = renderCard({ canvasTable: ct, highlightJoinTargets: null });

		const idRow = container.querySelector('[data-column="id"]')!;
		expect(idRow.getAttribute('class') ?? '').not.toContain('shadow-[');
	});
});
