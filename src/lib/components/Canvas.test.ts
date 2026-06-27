import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import Canvas from '$lib/components/Canvas.svelte';
import { query } from '$lib/stores/query.svelte';
import type { TableInfo } from '$lib/ir';

const usersTable: TableInfo = {
	schema: 'public',
	name: 'users',
	columns: [
		{ name: 'id', data_type: 'int', nullable: false, is_indexed: true },
		{ name: 'email', data_type: 'varchar', nullable: true, is_indexed: false }
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
	indexes: [{ name: 'orders_pkey', columns: ['id'], is_unique: true, is_primary: true }]
};

function seed(tables: TableInfo[] = [usersTable, ordersTable]) {
	query.clear();
	for (const t of tables) query.addTable(t);
}

describe('Canvas', () => {
	beforeEach(() => {
		query.clear();
	});

	afterEach(() => {
		cleanup();
		query.clear();
	});

	it('renders one TableCard per query table', () => {
		seed();
		const { container } = render(Canvas);
		// The outer TableCard div has data-alias={alias}; column rows also have
		// data-alias, so we look for the drag-handle-bearing header instead.
		const handles = container.querySelectorAll('[data-drag-handle]');
		expect(handles.length).toBe(2);
	});

	it('status pill shows table/join/column/filter counts', async () => {
		seed();
		render(Canvas);
		// 2 tables, 0 joins, 0 columns, 0 filters.
		// Joins/columns/filters are only shown in the pill when > 0, so only
		// "2 tables" is visible.
		expect(await screen.findByText(/2 tables/)).toBeInTheDocument();
	});

	it('status pill shows join count when there are joins', async () => {
		seed();
		query.addJoin({ left_alias: 't0', left_column: 'id', right_alias: 't1', right_column: 'user_id' });
		render(Canvas);
		expect(await screen.findByText(/2 tables · 1 join/)).toBeInTheDocument();
	});

	it('table drag: mousedown on [data-drag-handle] + mousemove calls moveTable', async () => {
		seed();
		const { container } = render(Canvas);

		// mousedown on the drag handle for t0
		const handle = container.querySelector('[data-drag-handle="t0"]') as HTMLElement;
		expect(handle).toBeTruthy();
		fireEvent.mouseDown(handle, { clientX: 100, clientY: 100 });

		// A mousemove should move the table
		fireEvent.mouseMove(window, { clientX: 250, clientY: 175 });
		const t0 = query.tables.find((t) => t.alias === 't0')!;
		expect(t0.x).toBeGreaterThan(40); // moved from initial 40
		expect(t0.y).toBeGreaterThan(40);

		// mouseup releases the drag
		fireEvent.mouseUp(window);
	});

	it('join drop on a different indexed column calls addJoin with the right args', () => {
		seed();
		const { container } = render(Canvas);

		// Start the join drag from t0.id (the join handle in TableCard fires onmousedown
		// which propagates to the canvas's mousedown handler — we simulate that by
		// firing mousedown on the join handle with the same client coordinates).
		const sourceHandle = container.querySelector(
			'[data-join-source-alias="t0"][data-join-source-column="id"]'
		) as HTMLElement;
		expect(sourceHandle).toBeTruthy();

		// The canvas listens for mousedown on the canvas root to start table drags.
		// Join drag is started via TableCard's onmousedown which calls onStartJoin
		// prop. We simulate this by directly calling the mousedown flow that the
		// join handle triggers — which means we trigger mouseDown on the source
		// handle and then mouseUp on the target column row.
		// However, TableCard's onmousedown bubbles to the canvas's mousedown handler
		// first, which would try to start a TABLE drag. The handle is not a
		// [data-drag-handle], so the canvas's table drag won't start. Then the
		// bubble reaches TableCard's onmousedown, which calls onStartJoin.
		// Since we don't have a real DOM with a TableCard mousedown wired up
		// in this isolated Canvas test, we instead simulate the join drag state
		// by directly calling the join handle's mousedown and then mouseup on
		// the target.

		// Trigger mousedown on the source join handle. Because the canvas's
		// mousedown handler runs first and the join handle is not a drag-handle,
		// the table drag is NOT started. Then the TableCard's mousedown fires
		// onStartJoin via the join-handle's onmousedown handler.
		// (The onStartJoin prop is wired up by the Canvas's handleStartJoin.)

		// To make this work, we need to find a way to trigger handleStartJoin.
		// Since the join handle's onmousedown is bound to onStartJoin, we can
		// fire a mousedown on it and rely on the event bubbling.
		fireEvent.mouseDown(sourceHandle, { clientX: 100, clientY: 100 });

		// Now mouseup over a different indexed column.
		// Get the target row for t1.user_id (an indexed column).
		const targetRow = container.querySelector(
			'[data-alias="t1"][data-column="user_id"]'
		) as HTMLElement;
		expect(targetRow).toBeTruthy();

		// Fire a mouseup event on the window with clientX/clientY that hit the
		// target row. document.elementFromPoint is used by Canvas to find the
		// target. We need to mock it.
		const rect = { left: 0, top: 0, width: 1000, height: 1000, right: 1000, bottom: 1000, x: 0, y: 0, toJSON: () => ({}) };
		targetRow.getBoundingClientRect = () => rect as DOMRect;
		// document.elementFromPoint should return the target row.
		const origElementFromPoint = document.elementFromPoint;
		document.elementFromPoint = ((x: number, y: number) => {
			// Return targetRow for any reasonable point.
			if (x > 0 && y > 0) return targetRow;
			return null;
		}) as typeof document.elementFromPoint;

		try {
			fireEvent.mouseUp(window, { clientX: 200, clientY: 200 });

			expect(query.joins).toHaveLength(1);
			expect(query.joins[0]).toEqual({
				left_alias: 't0',
				left_column: 'id',
				right_alias: 't1',
				right_column: 'user_id'
			});
		} finally {
			document.elementFromPoint = origElementFromPoint;
		}
	});

	it('join drop on the same column is a no-op (no addJoin call)', () => {
		seed();
		const { container } = render(Canvas);

		const sourceHandle = container.querySelector(
			'[data-join-source-alias="t0"][data-join-source-column="id"]'
		) as HTMLElement;
		fireEvent.mouseDown(sourceHandle, { clientX: 100, clientY: 100 });

		// Drop on the same column.
		const sameRow = container.querySelector(
			'[data-alias="t0"][data-column="id"]'
		) as HTMLElement;
		const rect = { left: 0, top: 0, width: 1000, height: 1000, right: 1000, bottom: 1000, x: 0, y: 0, toJSON: () => ({}) };
		sameRow.getBoundingClientRect = () => rect as DOMRect;
		const origElementFromPoint = document.elementFromPoint;
		document.elementFromPoint = (() => sameRow) as typeof document.elementFromPoint;

		try {
			fireEvent.mouseUp(window, { clientX: 200, clientY: 200 });
			expect(query.joins).toHaveLength(0);
		} finally {
			document.elementFromPoint = origElementFromPoint;
		}
	});

	it('join drop on a non-indexed target is a no-op', () => {
		seed();
		const { container } = render(Canvas);

		const sourceHandle = container.querySelector(
			'[data-join-source-alias="t0"][data-join-source-column="id"]'
		) as HTMLElement;
		fireEvent.mouseDown(sourceHandle, { clientX: 100, clientY: 100 });

		// Drop on email (not indexed).
		const emailRow = container.querySelector(
			'[data-alias="t0"][data-column="email"]'
		) as HTMLElement;
		const origElementFromPoint = document.elementFromPoint;
		document.elementFromPoint = (() => emailRow) as typeof document.elementFromPoint;

		try {
			fireEvent.mouseUp(window, { clientX: 200, clientY: 200 });
			expect(query.joins).toHaveLength(0);
		} finally {
			document.elementFromPoint = origElementFromPoint;
		}
	});

	it('clicking a join hit path calls removeJoin', () => {
		seed();
		query.addJoin({ left_alias: 't0', left_column: 'id', right_alias: 't1', right_column: 'user_id' });
		const { container } = render(Canvas);

		const hitPath = container.querySelector('path[role="button"][aria-label*="Remove join"]') as SVGPathElement;
		expect(hitPath).toBeTruthy();

		fireEvent.click(hitPath);
		expect(query.joins).toHaveLength(0);
	});

	it('Enter / Space / Delete / Backspace on a focused join hit path remove the join', () => {
		seed();
		query.addJoin({ left_alias: 't0', left_column: 'id', right_alias: 't1', right_column: 'user_id' });
		const { container } = render(Canvas);

		const hitPath = container.querySelector('path[role="button"][aria-label*="Remove join"]') as SVGPathElement;
		expect(hitPath).toBeTruthy();

		for (const key of ['Enter', ' ', 'Delete', 'Backspace']) {
			query.addJoin({ left_alias: 't0', left_column: 'id', right_alias: 't1', right_column: 'user_id' });
			fireEvent.keyDown(hitPath, { key });
			expect(query.joins, `after ${key}`).toHaveLength(0);
		}
	});

	it('unrelated key presses on the join do not remove it', () => {
		seed();
		query.addJoin({ left_alias: 't0', left_column: 'id', right_alias: 't1', right_column: 'user_id' });
		const { container } = render(Canvas);

		const hitPath = container.querySelector('path[role="button"][aria-label*="Remove join"]') as SVGPathElement;
		fireEvent.keyDown(hitPath, { key: 'a' });
		expect(query.joins).toHaveLength(1);
	});

	it('focusing a join renders the glow path with non-zero opacity', async () => {
		seed();
		query.addJoin({ left_alias: 't0', left_column: 'id', right_alias: 't1', right_column: 'user_id' });
		const { container } = render(Canvas);

		// The focus-glow path has stroke #0ea5e9 and stroke-width 6 with
		// class:opacity-0 bound to focusedJoinIndex !== i.
		const glow = Array.from(container.querySelectorAll('path')).find(
			(p) => p.getAttribute('stroke') === '#0ea5e9' && p.getAttribute('stroke-width') === '6'
		) as SVGPathElement | undefined;
		expect(glow).toBeTruthy();

		// Initially opacity is 0 because no join is focused.
		expect(glow!.classList.contains('opacity-0')).toBe(true);

		const hitPath = container.querySelector('path[role="button"][aria-label*="Remove join"]') as SVGPathElement;
		hitPath.focus();

		// Svelte 5 reactivity may flush asynchronously; wait for the class to
		// be removed.
		await waitFor(() => {
			expect(glow!.classList.contains('opacity-0')).toBe(false);
		});
	});
});
