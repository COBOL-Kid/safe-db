import { invoke } from '@tauri-apps/api/core';
import type { ConnectionDef, HistoryEntry, QueryResult, QuerySpec, SavedQuery, Schema, Settings } from './ir';

export async function testConnection(def: ConnectionDef, password: string): Promise<string> {
	return invoke<string>('test_connection', { def, password });
}

export async function saveConnection(def: ConnectionDef, password: string | null): Promise<void> {
	return invoke<void>('save_connection', { def, password });
}

export async function listConnections(): Promise<ConnectionDef[]> {
	return invoke<ConnectionDef[]>('list_connections');
}

export async function deleteConnection(id: string): Promise<void> {
	return invoke<void>('delete_connection', { id });
}

export async function getSchema(connectionId: string): Promise<Schema> {
	return invoke<Schema>('get_schema', { connectionId });
}

export async function runQuery(
	connectionId: string,
	spec: QuerySpec,
	force = false
): Promise<QueryResult> {
	return invoke<QueryResult>('run_query', { connectionId, spec, force });
}

export async function listSavedQueries(): Promise<SavedQuery[]> {
	return invoke<SavedQuery[]>('list_saved_queries');
}

export async function saveSavedQuery(query: SavedQuery): Promise<void> {
	return invoke<void>('save_saved_query', { query });
}

export async function deleteSavedQuery(id: string): Promise<void> {
	return invoke<void>('delete_saved_query', { id });
}

export async function listHistory(): Promise<HistoryEntry[]> {
	return invoke<HistoryEntry[]>('list_history');
}

export async function clearHistory(): Promise<void> {
	return invoke<void>('clear_history');
}

export async function getSettings(): Promise<Settings> {
	return invoke<Settings>('get_settings');
}

export async function saveSettings(settings: Settings): Promise<void> {
	return invoke<void>('save_settings', { settings });
}
