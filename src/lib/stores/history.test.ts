import { beforeEach, describe, expect, it, vi } from 'vitest';
import { HistoryStore } from '$lib/stores/history.svelte';
import * as api from '$lib/api';
import type { HistoryEntry } from '$lib/ir';

vi.mock('$lib/api');

const sampleEntry: HistoryEntry = {
	id: 'h1',
	connection_id: 'c1',
	connection_name: 'Test DB',
	spec: {
		tables: [],
		columns: [],
		joins: [],
		filters: { id: 'g', connector: 'And', children: [] },
		limit: 100,
		schema_version: 2,
		connector_overrides: {}
	},
	row_count: 5,
	warnings: [],
	error: null,
	timestamp: '1700000000'
};

describe('HistoryStore', () => {
	let store: HistoryStore;

	beforeEach(() => {
		store = new HistoryStore();
		vi.mocked(api.listHistory).mockReset();
		vi.mocked(api.clearHistory).mockReset();
	});

	it('load populates entries and clears loading', async () => {
		vi.mocked(api.listHistory).mockResolvedValue([sampleEntry]);

		await store.load();

		expect(api.listHistory).toHaveBeenCalledOnce();
		expect(store.entries).toEqual([sampleEntry]);
		expect(store.loading).toBe(false);
	});

	it('load swallows errors and resets to empty list', async () => {
		vi.mocked(api.listHistory).mockRejectedValue(new Error('boom'));

		await store.load();

		expect(store.entries).toEqual([]);
		expect(store.loading).toBe(false);
	});

	it('clear invokes api.clearHistory and empties entries', async () => {
		store.entries = [sampleEntry];
		vi.mocked(api.clearHistory).mockResolvedValue();

		await store.clear();

		expect(api.clearHistory).toHaveBeenCalledOnce();
		expect(store.entries).toEqual([]);
	});

	it('clear propagates errors and leaves entries untouched', async () => {
		store.entries = [sampleEntry];
		vi.mocked(api.clearHistory).mockRejectedValue(new Error('boom'));

		await expect(store.clear()).rejects.toThrow('boom');
		expect(store.entries).toEqual([sampleEntry]);
	});
});
