import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SchemaStore } from '$lib/stores/schema.svelte';
import * as api from '$lib/api';
import type { Schema } from '$lib/ir';

vi.mock('$lib/api');

const sampleSchema: Schema = {
	tables: [
		{
			schema: 'public',
			name: 'users',
			columns: [],
			indexes: []
		},
		{
			schema: 'public',
			name: 'orders',
			columns: [],
			indexes: []
		}
	]
};

describe('SchemaStore', () => {
	let store: SchemaStore;

	beforeEach(() => {
		store = new SchemaStore();
		vi.mocked(api.getSchema).mockReset();
	});

	it('filters tables by search case-insensitively', () => {
		store.schema = sampleSchema;
		store.search = 'USER';
		expect(store.filteredTables.map((t) => t.name)).toEqual(['users']);
	});

	it('returns all tables when search is empty', () => {
		store.schema = sampleSchema;
		store.search = '  ';
		expect(store.filteredTables).toHaveLength(2);
	});

	it('loads schema from api and records connection id', async () => {
		vi.mocked(api.getSchema).mockResolvedValue(sampleSchema);
		await store.load('conn-1');
		expect(store.schema).toEqual(sampleSchema);
		expect(store.loadedConnectionId).toBe('conn-1');
		expect(store.loading).toBe(false);
	});

	it('captures load errors', async () => {
		vi.mocked(api.getSchema).mockRejectedValue(new Error('network down'));
		await store.load('conn-2');
		expect(store.error).toContain('network down');
		expect(store.schema).toBeNull();
		expect(store.loadedConnectionId).toBeNull();
	});

	it('retries load after failure', async () => {
		vi.mocked(api.getSchema)
			.mockRejectedValueOnce(new Error('network down'))
			.mockResolvedValueOnce(sampleSchema);
		await store.load('conn-1');
		expect(store.error).toContain('network down');
		await store.load('conn-1');
		expect(store.schema).toEqual(sampleSchema);
		expect(store.error).toBeNull();
		expect(api.getSchema).toHaveBeenCalledTimes(2);
	});

	it('skips refetch when schema is already loaded for the connection', async () => {
		vi.mocked(api.getSchema).mockResolvedValue(sampleSchema);
		await store.load('conn-1');
		await store.load('conn-1');
		expect(api.getSchema).toHaveBeenCalledTimes(1);
	});
});
