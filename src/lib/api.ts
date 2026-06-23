import { invoke } from '@tauri-apps/api/core';
import type { ConnectionDef, QueryResult, QuerySpec, Schema } from './ir';

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

export async function runQuery(connectionId: string, spec: QuerySpec): Promise<QueryResult> {
	return invoke<QueryResult>('run_query', { connectionId, spec });
}
