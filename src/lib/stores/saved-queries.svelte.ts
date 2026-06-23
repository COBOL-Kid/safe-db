import { browser } from '$app/environment';
import type { SavedQuery } from '$lib/ir';
import * as api from '$lib/api';

class SavedQueriesStore {
	queries = $state<SavedQuery[]>([]);
	loading = $state(false);

	async load() {
		if (!browser) return;
		this.loading = true;
		try {
			this.queries = await api.listSavedQueries();
		} catch {
			this.queries = [];
		} finally {
			this.loading = false;
		}
	}

	async save(query: SavedQuery) {
		await api.saveSavedQuery(query);
		await this.load();
	}

	async remove(id: string) {
		await api.deleteSavedQuery(id);
		this.queries = this.queries.filter((q) => q.id !== id);
	}

	forConnection(connectionId: string): SavedQuery[] {
		return this.queries.filter((q) => q.connection_id === connectionId);
	}
}

export const savedQueries = new SavedQueriesStore();
