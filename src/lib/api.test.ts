import { invoke } from '@tauri-apps/api/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
	clearHistory,
	deleteConnection,
	deleteSavedQuery,
	getSchema,
	getSettings,
	listConnections,
	listHistory,
	listSavedQueries,
	runQuery,
	saveConnection,
	saveSavedQuery,
	saveSettings,
	testConnection
} from '$lib/api';
import type { ConnectionDef, QuerySpec, SavedQuery, Settings } from '$lib/ir';

vi.mock('@tauri-apps/api/core', () => ({
	invoke: vi.fn()
}));

const invokeMock = vi.mocked(invoke);

const def: ConnectionDef = {
	version: 2,
	id: 'c1',
	name: 'Local PG',
	dialect: 'Postgres',
	host: 'localhost',
	port: 5432,
	database: 'app',
	username: 'app',
	transport_security: {
		mode: 'VerifyIdentity'
	}
};

describe('api wrappers', () => {
	beforeEach(() => {
		invokeMock.mockReset();
	});

	describe('connections', () => {
		it('testConnection invokes test_connection with def + password (preserves empty string)', async () => {
			invokeMock.mockResolvedValue('PostgreSQL 16');
			const result = await testConnection(def, '');
			expect(invokeMock).toHaveBeenCalledWith('test_connection', { def, password: '' });
			expect(result).toBe('PostgreSQL 16');
		});

		it('saveConnection passes null password through as null', async () => {
			invokeMock.mockResolvedValue(undefined);
			await saveConnection(def, null);
			expect(invokeMock).toHaveBeenCalledWith('save_connection', { def, password: null });
		});

		it('saveConnection passes an empty string password through as empty string', async () => {
			invokeMock.mockResolvedValue(undefined);
			await saveConnection(def, '');
			expect(invokeMock).toHaveBeenCalledWith('save_connection', { def, password: '' });
		});

		it('listConnections invokes list_connections with no args', async () => {
			invokeMock.mockResolvedValue([def]);
			const result = await listConnections();
			expect(invokeMock).toHaveBeenCalledWith('list_connections');
			expect(result).toEqual([def]);
		});

		it('deleteConnection invokes delete_connection with the id', async () => {
			invokeMock.mockResolvedValue(undefined);
			await deleteConnection('c1');
			expect(invokeMock).toHaveBeenCalledWith('delete_connection', { id: 'c1' });
		});
	});

	describe('schema + query', () => {
		it('getSchema passes connectionId', async () => {
			const schema = { tables: [] };
			invokeMock.mockResolvedValue(schema);
			const result = await getSchema('c1');
			expect(invokeMock).toHaveBeenCalledWith('get_schema', { connectionId: 'c1' });
			expect(result).toBe(schema);
		});

		it('runQuery passes connectionId and spec', async () => {
			const spec: QuerySpec = {
				tables: [],
				columns: [],
				joins: [],
				filters: { id: 'g', connector: 'And', children: [] },
				limit: 100,
				schema_version: 2,
				connector_overrides: {}
			};
			const result = { columns: [], rows: [], row_count: 0, truncated: false, warnings: [] };
			invokeMock.mockResolvedValue(result);
			const r = await runQuery('c1', spec);
			expect(invokeMock).toHaveBeenCalledWith('run_query', { connectionId: 'c1', spec, force: false });
			expect(r).toBe(result);
		});
	});

	describe('saved queries', () => {
		it('listSavedQueries invokes list_saved_queries', async () => {
			invokeMock.mockResolvedValue([]);
			const result = await listSavedQueries();
			expect(invokeMock).toHaveBeenCalledWith('list_saved_queries');
			expect(result).toEqual([]);
		});

		it('saveSavedQuery invokes save_saved_query with the query', async () => {
			const sq: SavedQuery = {
				id: 'q1',
				name: 'Newest',
				connection_id: 'c1',
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
			invokeMock.mockResolvedValue(undefined);
			await saveSavedQuery(sq);
			expect(invokeMock).toHaveBeenCalledWith('save_saved_query', { query: sq });
		});

		it('deleteSavedQuery invokes delete_saved_query with the id', async () => {
			invokeMock.mockResolvedValue(undefined);
			await deleteSavedQuery('q1');
			expect(invokeMock).toHaveBeenCalledWith('delete_saved_query', { id: 'q1' });
		});
	});

	describe('history', () => {
		it('listHistory invokes list_history', async () => {
			invokeMock.mockResolvedValue([]);
			const result = await listHistory();
			expect(invokeMock).toHaveBeenCalledWith('list_history');
			expect(result).toEqual([]);
		});

		it('clearHistory invokes clear_history with no args', async () => {
			invokeMock.mockResolvedValue(undefined);
			await clearHistory();
			expect(invokeMock).toHaveBeenCalledWith('clear_history');
		});
	});

	describe('settings', () => {
		it('getSettings invokes get_settings', async () => {
			const s: Settings = { blocked_schemas: [], explain_cost_threshold: 100000, theme: 'light' };
			invokeMock.mockResolvedValue(s);
			const result = await getSettings();
			expect(invokeMock).toHaveBeenCalledWith('get_settings');
			expect(result).toBe(s);
		});

		it('saveSettings passes settings under the settings key', async () => {
			invokeMock.mockResolvedValue(undefined);
			const s: Settings = { blocked_schemas: ['pg_catalog'], explain_cost_threshold: 50, theme: 'dark' };
			await saveSettings(s);
			expect(invokeMock).toHaveBeenCalledWith('save_settings', { settings: s });
		});
	});
});
