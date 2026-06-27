import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SavedQueriesStore } from '$lib/stores/saved-queries.svelte';
import * as api from '$lib/api';
import type { SavedQuery } from '$lib/ir';

vi.mock('$lib/api');

function makeQuery(id: string, connectionId: string, name: string): SavedQuery {
	return {
		id,
		name,
		connection_id: connectionId,
		spec: {
			tables: [],
			columns: [],
			joins: [],
			filters: { id: 'g', connector: 'And', children: [] },
			limit: 100,
			schema_version: 2,
			connector_overrides: {}
		},
		created_at: '1700000000'
	};
}

describe('SavedQueriesStore', () => {
	let store: SavedQueriesStore;

	beforeEach(() => {
		store = new SavedQueriesStore();
		vi.mocked(api.listSavedQueries).mockReset();
		vi.mocked(api.saveSavedQuery).mockReset();
		vi.mocked(api.deleteSavedQuery).mockReset();
	});

	it('load populates queries and clears loading', async () => {
		const data = [makeQuery('q1', 'c1', 'Q1')];
		vi.mocked(api.listSavedQueries).mockResolvedValue(data);

		await store.load();

		expect(api.listSavedQueries).toHaveBeenCalledOnce();
		expect(store.queries).toEqual(data);
		expect(store.loading).toBe(false);
	});

	it('load swallows errors and resets to empty list', async () => {
		vi.mocked(api.listSavedQueries).mockRejectedValue(new Error('boom'));

		await store.load();

		expect(store.queries).toEqual([]);
		expect(store.loading).toBe(false);
	});

	it('save forwards the query and refreshes the list', async () => {
		const q = makeQuery('q1', 'c1', 'Q1');
		vi.mocked(api.saveSavedQuery).mockResolvedValue();
		vi.mocked(api.listSavedQueries).mockResolvedValue([q]);

		await store.save(q);

		expect(api.saveSavedQuery).toHaveBeenCalledWith(q);
		expect(api.listSavedQueries).toHaveBeenCalledOnce();
		expect(store.queries).toEqual([q]);
	});

	it('remove calls api.deleteSavedQuery and optimistically drops from the list', async () => {
		store.queries = [makeQuery('q1', 'c1', 'Q1'), makeQuery('q2', 'c1', 'Q2')];
		vi.mocked(api.deleteSavedQuery).mockResolvedValue();

		await store.remove('q1');

		expect(api.deleteSavedQuery).toHaveBeenCalledWith('q1');
		expect(store.queries.map((q) => q.id)).toEqual(['q2']);
	});

	it('remove propagates errors and leaves the list untouched', async () => {
		store.queries = [makeQuery('q1', 'c1', 'Q1')];
		vi.mocked(api.deleteSavedQuery).mockRejectedValue(new Error('boom'));

		await expect(store.remove('q1')).rejects.toThrow('boom');
		expect(store.queries.map((q) => q.id)).toEqual(['q1']);
	});

	it('forConnection returns only queries for the given connection', () => {
		store.queries = [
			makeQuery('q1', 'c1', 'Q1'),
			makeQuery('q2', 'c2', 'Q2'),
			makeQuery('q3', 'c1', 'Q3')
		];

		expect(store.forConnection('c1').map((q) => q.id)).toEqual(['q1', 'q3']);
		expect(store.forConnection('c2').map((q) => q.id)).toEqual(['q2']);
		expect(store.forConnection('unknown')).toEqual([]);
	});
});
