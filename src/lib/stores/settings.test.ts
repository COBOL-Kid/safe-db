import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SettingsStore } from '$lib/stores/settings.svelte';
import * as api from '$lib/api';

vi.mock('$lib/api');

describe('SettingsStore', () => {
	let store: SettingsStore;

	beforeEach(() => {
		store = new SettingsStore();
		vi.mocked(api.getSettings).mockReset();
		vi.mocked(api.saveSettings).mockReset();
		document.documentElement.classList.remove('dark');
	});

	it('falls back to defaults when load fails', async () => {
		vi.mocked(api.getSettings).mockRejectedValue(new Error('missing'));
		await store.load();
		expect(store.settings.theme).toBe('light');
		expect(store.settings.explain_cost_threshold).toBe(100_000);
		expect(store.settings.blocked_schemas).toEqual([]);
	});

	it('toggles theme and persists', async () => {
		vi.mocked(api.getSettings).mockResolvedValue({
			blocked_schemas: [],
			explain_cost_threshold: 100_000,
			theme: 'light'
		});
		vi.mocked(api.saveSettings).mockResolvedValue();
		await store.load();
		await store.toggleTheme();
		expect(store.settings.theme).toBe('dark');
		expect(document.documentElement.classList.contains('dark')).toBe(true);
		expect(api.saveSettings).toHaveBeenCalled();
	});

	it('deduplicates blocked schemas on add', async () => {
		store.settings = {
			blocked_schemas: ['audit'],
			explain_cost_threshold: 100_000,
			theme: 'light'
		};
		vi.mocked(api.saveSettings).mockResolvedValue();
		await store.addBlockedSchema('audit');
		expect(store.settings.blocked_schemas).toEqual(['audit']);
		expect(api.saveSettings).not.toHaveBeenCalled();

		await store.addBlockedSchema('staging');
		expect(store.settings.blocked_schemas).toEqual(['audit', 'staging']);
	});

	it('removes blocked schemas', async () => {
		store.settings = {
			blocked_schemas: ['audit', 'staging'],
			explain_cost_threshold: 100_000,
			theme: 'light'
		};
		vi.mocked(api.saveSettings).mockResolvedValue();
		await store.removeBlockedSchema('audit');
		expect(store.settings.blocked_schemas).toEqual(['staging']);
	});
});
