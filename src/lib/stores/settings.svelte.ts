import { browser } from '$app/environment';
import type { Settings } from '$lib/ir';
import * as api from '$lib/api';
import { syncWindowBackgroundColor } from '$lib/window';

const defaultSettings: Settings = {
	blocked_schemas: [],
	explain_cost_threshold: 100_000,
	theme: 'light'
};

class SettingsStore {
	settings = $state<Settings>({ ...defaultSettings });
	loading = $state(false);

	get theme() {
		return this.settings.theme;
	}

	get isDark() {
		return this.settings.theme === 'dark';
	}

	async load() {
		if (!browser) return;
		this.loading = true;
		try {
			this.settings = await api.getSettings();
		} catch {
			this.settings = { ...defaultSettings };
		} finally {
			this.loading = false;
		}
		this.applyTheme();
	}

	async toggleTheme() {
		this.settings.theme = this.settings.theme === 'light' ? 'dark' : 'light';
		await this.save();
	}

	async save() {
		await api.saveSettings(this.settings);
		this.applyTheme();
	}

	async addBlockedSchema(schema: string) {
		if (!this.settings.blocked_schemas.includes(schema)) {
			this.settings.blocked_schemas = [...this.settings.blocked_schemas, schema];
			await this.save();
		}
	}

	async removeBlockedSchema(schema: string) {
		this.settings.blocked_schemas = this.settings.blocked_schemas.filter((s) => s !== schema);
		await this.save();
	}

	applyTheme() {
		if (!browser) return;
		document.documentElement.classList.toggle('dark', this.isDark);
		void syncWindowBackgroundColor(this.settings.theme);
	}
}

export { SettingsStore };
export const settings = new SettingsStore();
