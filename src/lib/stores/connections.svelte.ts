import { browser } from '$app/environment';
import type { ConnectionDef } from '$lib/ir';
import * as api from '$lib/api';
import { query } from '$lib/stores/query.svelte';
import { schema } from '$lib/stores/schema.svelte';

class ConnectionStore {
	connections = $state<ConnectionDef[]>([]);
	loading = $state(false);
	error = $state<string | null>(null);
	deleteError = $state<string | null>(null);

	activeId = $state<string | null>(null);
	pendingActivation = $state<ConnectionDef | null>(null);
	private activationResolver: ((confirmed: boolean) => void) | null = null;

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

	private requestActivationConfirmation(connection: ConnectionDef): Promise<boolean> {
		this.activationResolver?.(false);
		this.pendingActivation = connection;
		return new Promise((resolve) => {
			this.activationResolver = resolve;
		});
	}

	confirmActivation() {
		this.pendingActivation = null;
		this.activationResolver?.(true);
		this.activationResolver = null;
	}

	cancelActivation() {
		this.pendingActivation = null;
		this.activationResolver?.(false);
		this.activationResolver = null;
	}

	async activate(id: string): Promise<boolean> {
		const connection = this.connections.find((item) => item.id === id);
		if (!connection) {
			this.error = 'Connection not found';
			return false;
		}
		if (this.activeId !== id && query.tables.length > 0) {
			const confirmed = await this.requestActivationConfirmation(connection);
			if (!confirmed) return false;
		}
		if (this.activeId !== id) query.clear();
		schema.clear();
		const loaded = await schema.load(id);
		if (!loaded) return false;
		this.activeId = id;
		return true;
	}

	async addOrUpdate(def: ConnectionDef, password: string | null) {
		const exists = this.connections.some((connection) => connection.id === def.id);
		if (exists) {
			await api.updateConnection(def, password);
		} else {
			if (password === null) throw new Error('A password is required when creating a connection');
			await api.createConnection(def, password);
		}
		await this.load();
	}
}

export { ConnectionStore };
export const connections = new ConnectionStore();
