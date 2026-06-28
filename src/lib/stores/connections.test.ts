import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ConnectionStore } from '$lib/stores/connections.svelte';
import * as api from '$lib/api';

vi.mock('$lib/api');

const sampleConnection = {
	version: 2,
	id: 'c1',
	name: 'Test DB',
	dialect: 'Postgres' as const,
	host: 'localhost',
	port: 5432,
	database: 'demo',
	username: 'user',
	transport_security: {
		mode: 'VerifyIdentity' as const
	}
};

describe('ConnectionStore', () => {
	let store: ConnectionStore;

	beforeEach(() => {
		store = new ConnectionStore();
		vi.mocked(api.deleteConnection).mockReset();
	});

	it('remove calls deleteConnection and removes from list', async () => {
		store.connections = [sampleConnection];
		vi.mocked(api.deleteConnection).mockResolvedValue();

		await store.remove('c1');

		expect(api.deleteConnection).toHaveBeenCalledWith('c1');
		expect(store.connections).toEqual([]);
		expect(store.deleteError).toBeNull();
	});

	it('remove sets deleteError without clobbering load error on failure', async () => {
		store.connections = [sampleConnection];
		store.error = 'load failed';
		vi.mocked(api.deleteConnection).mockRejectedValue(new Error('delete failed'));

		await store.remove('c1');

		expect(store.connections).toEqual([sampleConnection]);
		expect(store.deleteError).toBe('Error: delete failed');
		expect(store.error).toBe('load failed');
	});

	it('clearDeleteError clears deleteError', () => {
		store.deleteError = 'something went wrong';
		store.clearDeleteError();
		expect(store.deleteError).toBeNull();
	});

	it('remove clears activeId when deleting active connection', async () => {
		store.connections = [sampleConnection];
		store.activeId = 'c1';
		vi.mocked(api.deleteConnection).mockResolvedValue();

		await store.remove('c1');

		expect(store.activeId).toBeNull();
	});
});
