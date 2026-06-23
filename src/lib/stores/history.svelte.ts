import { browser } from '$app/environment';
import type { HistoryEntry } from '$lib/ir';
import * as api from '$lib/api';

class HistoryStore {
	entries = $state<HistoryEntry[]>([]);
	loading = $state(false);

	async load() {
		if (!browser) return;
		this.loading = true;
		try {
			this.entries = await api.listHistory();
		} catch {
			this.entries = [];
		} finally {
			this.loading = false;
		}
	}

	async clear() {
		await api.clearHistory();
		this.entries = [];
	}
}

export const history = new HistoryStore();
