import { browser } from '$app/environment';
import type { Schema } from '$lib/ir';
import * as api from '$lib/api';

class SchemaStore {
	schema = $state<Schema | null>(null);
	loading = $state(false);
	error = $state<string | null>(null);
	loadedConnectionId = $state<string | null>(null);

	search = $state('');

	tables = $derived(this.schema?.tables ?? []);

	filteredTables = $derived.by(() => {
		const q = this.search.trim().toLowerCase();
		if (!q) return this.tables;
		return this.tables.filter((t) => t.name.toLowerCase().includes(q));
	});

	async load(connectionId: string) {
		if (!browser) return;
		if (this.loadedConnectionId === connectionId && this.schema) return;
		this.loading = true;
		this.error = null;
		this.schema = null;
		try {
			this.schema = await api.getSchema(connectionId);
			this.loadedConnectionId = connectionId;
		} catch (e) {
			this.error = String(e);
			this.loadedConnectionId = null;
		} finally {
			this.loading = false;
		}
	}

	clear() {
		this.schema = null;
		this.loadedConnectionId = null;
		this.error = null;
		this.search = '';
	}
}

export { SchemaStore };
export const schema = new SchemaStore();
