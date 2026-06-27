import { describe, expect, it } from 'vitest';
import { columnKey, parseColumnKey, columnKeyPrefix } from './column-keys';

describe('column-keys', () => {
	it('round-trips alias and column with null separator', () => {
		const key = columnKey('t0', 'name');
		expect(parseColumnKey(key)).toEqual({ alias: 't0', column: 'name' });
	});

	it('handles dots in column names', () => {
		const key = columnKey('t0', 'foo.bar');
		expect(parseColumnKey(key)).toEqual({ alias: 't0', column: 'foo.bar' });
	});

	it('columnKeyPrefix matches keys for alias', () => {
		const key = columnKey('t1', 'id');
		expect(key.startsWith(columnKeyPrefix('t1'))).toBe(true);
	});

	it('parses legacy dot-separated keys', () => {
		expect(parseColumnKey('t0.name')).toEqual({ alias: 't0', column: 'name' });
	});
});
