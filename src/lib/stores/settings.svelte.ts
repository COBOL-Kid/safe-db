import { browser } from '$app/environment';
import type { Dialect, Settings } from '$lib/ir';
import * as api from '$lib/api';
import { syncWindowBackgroundColor } from '$lib/window';

export const DEFAULT_EXPLAIN_COST_THRESHOLDS: Record<Dialect, number> = {
	Postgres: 100_000,
	MySql: 100_000,
	Mssql: 100_000,
	Oracle: 100_000
};

const defaultSettings: Settings = {
	blocked_schemas: [],
	explain_cost_threshold: 100_000,
	explain_cost_thresholds: { ...DEFAULT_EXPLAIN_COST_THRESHOLDS },
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
			this.settings = normalizeSettings(await api.getSettings());
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
		const normalized = schema.trim().toLowerCase();
		if (!normalized || this.settings.blocked_schemas.includes(normalized)) return;
		this.settings.blocked_schemas = [...this.settings.blocked_schemas, normalized];
		await this.save();
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

function normalizeSettings(value: Settings): Settings {
	const scalar = value.explain_cost_threshold;
	return {
		...value,
		explain_cost_thresholds: {
			Postgres: value.explain_cost_thresholds?.Postgres ?? scalar,
			MySql: value.explain_cost_thresholds?.MySql ?? scalar,
			Mssql: value.explain_cost_thresholds?.Mssql ?? scalar,
			Oracle: value.explain_cost_thresholds?.Oracle ?? scalar
		}
	};
}
