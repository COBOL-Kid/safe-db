import type { TableInfo } from './ir';

export interface CanvasTableLike {
	alias: string;
	x: number;
	y: number;
	width?: number;
	height?: number;
	tableInfo: TableInfo;
}

export const CANVAS_CARD_WIDTH = 224;
export const CANVAS_CARD_HEIGHT = 297;
export const CANVAS_HEADER_HEIGHT = 41;
export const CANVAS_ROW_HEIGHT = 28;

/** Top of the row containing `columnName` inside the given table card. */
export function columnY(
	ct: CanvasTableLike,
	columnName: string,
	cardWidth = CANVAS_CARD_WIDTH,
	headerHeight = CANVAS_HEADER_HEIGHT,
	rowHeight = CANVAS_ROW_HEIGHT
): number {
	const idx = ct.tableInfo.columns.findIndex((c) => c.name === columnName);
	return ct.y + headerHeight + idx * rowHeight + rowHeight / 2;
}

/** Right edge of the card for the given table. */
export function tableRightX(ct: CanvasTableLike, cardWidth = CANVAS_CARD_WIDTH): number {
	return ct.x + (ct.width ?? cardWidth);
}

/** Left edge of the card for the given table. */
export function tableLeftX(ct: CanvasTableLike): number {
	return ct.x;
}

/** Cubic Bezier path between two tables' join endpoints. */
export function joinEdgePath(
	left: CanvasTableLike,
	leftColumn: string,
	right: CanvasTableLike,
	rightColumn: string,
	cardWidth = CANVAS_CARD_WIDTH
): string {
	const sourceX = tableRightX(left, cardWidth);
	const targetX = tableLeftX(right);
	const sourceY = columnY(left, leftColumn, cardWidth);
	const targetY = columnY(right, rightColumn, cardWidth);
	const midX = (sourceX + targetX) / 2;
	return `M ${sourceX} ${sourceY} C ${midX} ${sourceY}, ${midX} ${targetY}, ${targetX} ${targetY}`;
}
