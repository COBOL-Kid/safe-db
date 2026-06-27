import { describe, expect, it } from 'vitest';
import { MAX_LIMIT } from '$lib/ir';
import { parseLimit } from '$lib/limits';

describe('parseLimit', () => {
	it('passes through values in the valid range', () => {
		expect(parseLimit(50)).toBe(50);
		expect(parseLimit('75')).toBe(75);
		expect(parseLimit(1)).toBe(1);
		expect(parseLimit(MAX_LIMIT)).toBe(MAX_LIMIT);
	});

	it('clamps values above MAX_LIMIT to MAX_LIMIT', () => {
		expect(parseLimit(MAX_LIMIT + 1)).toBe(MAX_LIMIT);
		expect(parseLimit('9999999')).toBe(MAX_LIMIT);
	});

	it('coerces NaN / zero / negatives to 1', () => {
		expect(parseLimit(0)).toBe(1);
		expect(parseLimit(-5)).toBe(1);
		expect(parseLimit('not a number')).toBe(1);
		expect(parseLimit('')).toBe(1);
	});

	it('parses strings with trailing garbage (parseInt-style)', () => {
		expect(parseLimit('25abc')).toBe(25);
	});
});
