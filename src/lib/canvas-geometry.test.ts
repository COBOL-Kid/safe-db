import { describe, expect, it } from 'vitest';
import {
	CANVAS_CARD_HEIGHT,
	CANVAS_CARD_WIDTH,
	CANVAS_HEADER_HEIGHT,
	CANVAS_ROW_HEIGHT,
	columnY,
	joinEdgePath,
	tableLeftX,
	tableRightX
} from '$lib/canvas-geometry';
import type { CanvasTableLike } from '$lib/canvas-geometry';

function canvas(x = 0, y = 0): CanvasTableLike {
	return {
		alias: 't0',
		x,
		y,
		tableInfo: {
			schema: 'public',
			name: 'users',
			columns: [
				{ name: 'id', data_type: 'int', nullable: false, is_indexed: true },
				{ name: 'email', data_type: 'varchar', nullable: true, is_indexed: false },
				{ name: 'name', data_type: 'varchar', nullable: true, is_indexed: false }
			],
			indexes: []
		}
	};
}

describe('canvas-geometry constants', () => {
	it('exports the same dimensions the Canvas component used to hard-code', () => {
		expect(CANVAS_CARD_WIDTH).toBe(224);
		expect(CANVAS_CARD_HEIGHT).toBe(297);
		expect(CANVAS_HEADER_HEIGHT).toBe(41);
		expect(CANVAS_ROW_HEIGHT).toBe(28);
	});
});

describe('columnY', () => {
	it('returns y + header + index*row + half-row for the named column', () => {
		const ct = canvas(100, 200);
		const y = columnY(ct, 'email');
		expect(y).toBe(200 + CANVAS_HEADER_HEIGHT + 1 * CANVAS_ROW_HEIGHT + CANVAS_ROW_HEIGHT / 2);
	});

	it('returns y + header + half-row for the first column (index 0)', () => {
		const ct = canvas(0, 0);
		expect(columnY(ct, 'id')).toBe(CANVAS_HEADER_HEIGHT + CANVAS_ROW_HEIGHT / 2);
	});

	it('respects custom cardWidth / headerHeight / rowHeight', () => {
		const ct = canvas(0, 0);
		const y = columnY(ct, 'email', 100, 10, 5);
		expect(y).toBe(10 + 1 * 5 + 5 / 2);
	});
});

describe('tableLeftX / tableRightX', () => {
	it('returns the card x and x + width', () => {
		const ct = canvas(120, 0);
		expect(tableLeftX(ct)).toBe(120);
		expect(tableRightX(ct)).toBe(120 + CANVAS_CARD_WIDTH);
	});

	it('honors a custom card width', () => {
		const ct = canvas(50, 0);
		expect(tableRightX(ct, 300)).toBe(350);
	});

	it('prefers the table width when present', () => {
		const ct = { ...canvas(50, 0), width: 360 };
		expect(tableRightX(ct)).toBe(410);
	});
});

describe('joinEdgePath', () => {
	it('returns a cubic Bezier starting at the left card right edge, ending at the right card left edge', () => {
		const left = canvas(0, 0);
		const right = canvas(CANVAS_CARD_WIDTH + 100, 0);
		const d = joinEdgePath(left, 'id', right, 'user_id');
		expect(d).toMatch(/^M \d+ \d+ C /);
		const sourceX = tableRightX(left);
		const targetX = tableLeftX(right);
		const midX = (sourceX + targetX) / 2;
		expect(d).toBe(
			`M ${sourceX} ${columnY(left, 'id')} C ${midX} ${columnY(left, 'id')}, ${midX} ${columnY(right, 'user_id')}, ${targetX} ${columnY(right, 'user_id')}`
		);
	});

	it('uses the left table width for the source endpoint', () => {
		const left = { ...canvas(0, 0), width: 320 };
		const right = canvas(500, 0);
		const d = joinEdgePath(left, 'id', right, 'email');
		expect(d.startsWith(`M 320 ${columnY(left, 'id')}`)).toBe(true);
	});
});
