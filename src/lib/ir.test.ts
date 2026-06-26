import { describe, expect, it } from 'vitest';
import { DEFAULT_LIMIT, MAX_LIMIT, qualifiedName } from '$lib/ir';
import type { TableInfo } from '$lib/ir';

describe('ir', () => {
	it('qualifiedName includes schema when present', () => {
		const table: TableInfo = {
			schema: 'public',
			name: 'users',
			columns: [],
			indexes: []
		};
		expect(qualifiedName(table)).toBe('public.users');
	});

	it('qualifiedName omits empty schema', () => {
		const table: TableInfo = {
			schema: '',
			name: 'users',
			columns: [],
			indexes: []
		};
		expect(qualifiedName(table)).toBe('users');
	});

	it('exports limit constants used by query store', () => {
		expect(DEFAULT_LIMIT).toBe(100);
		expect(MAX_LIMIT).toBe(1000);
	});
});
