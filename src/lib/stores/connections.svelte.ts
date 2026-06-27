import { browser } from '$app/environment';
import type { ConnectionDef } from '$lib/ir';
import * as api from '$lib/api';

class ConnectionStore {
	connections = $state<ConnectionDef[]>([]);
	loading = $state(false);
	error = $state<string | null>(null);
	deleteError = $state<string | null>(null);

	activeId = $state<string | null>(null);

	active = $derived(this.connections.find((c) => c.id === this.activeId) ?? null);

	async load() {
		if (!browser) return;
		this.loading = true;
		this.error = null;
		try {
			this.connections = await api.listConnections();
		} catch (e) {
			this.error = String(e);
		} finally {
			this.loading = false;
		}
	}

	async remove(id: string) {
		this.deleteError = null;
		try {
			await api.deleteConnection(id);
			this.connections = this.connections.filter((c) => c.id !== id);
			if (this.activeId === id) this.activeId = null;
		} catch (e) {
			this.deleteError = String(e);
		}
	}

	clearDeleteError() {
		this.deleteError = null;
	}

	setActive(id: string | null) {
		this.activeId = id;
	}

	async addOrUpdate(def: ConnectionDef, password: string | null) {
		await api.saveConnection(def, password);
		await this.load();
	}
}

export { ConnectionStore };
export const connections = new ConnectionStore();
