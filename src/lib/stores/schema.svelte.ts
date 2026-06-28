import { browser } from '$app/environment';
import type { Schema } from '$lib/ir';
import * as api from '$lib/api';

class SchemaStore {
	private requestGeneration = 0;
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

	async load(connectionId: string): Promise<boolean> {
		if (!browser) return false;
		if (this.loadedConnectionId === connectionId && this.schema) return true;
		const generation = ++this.requestGeneration;
		this.loading = true;
		this.error = null;
		this.schema = null;
		try {
			const loaded = await api.getSchema(connectionId);
			if (generation !== this.requestGeneration) return false;
			this.schema = loaded;
			this.loadedConnectionId = connectionId;
			return true;
		} catch (e) {
			if (generation !== this.requestGeneration) return false;
			this.error = String(e);
			this.loadedConnectionId = null;
			return false;
		} finally {
			if (generation === this.requestGeneration) this.loading = false;
		}
	}

	clear() {
		this.requestGeneration += 1;
		this.loading = false;
		this.schema = null;
		this.loadedConnectionId = null;
		this.error = null;
		this.search = '';
	}
}

export { SchemaStore };
export const schema = new SchemaStore();
