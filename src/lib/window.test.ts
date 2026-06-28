import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { syncWindowBackgroundColor } from '$lib/window';

const setBackgroundColor = vi.fn();
const getCurrentWebviewWindow = vi.fn(() => ({ setBackgroundColor }));

vi.mock('@tauri-apps/api/webviewWindow', () => ({
	getCurrentWebviewWindow
}));

const tauriInternalsKey = '__TAURI_INTERNALS__';

function setTauri(present: boolean) {
	if (present) {
		(window as unknown as Record<string, unknown>)[tauriInternalsKey] = {};
	} else {
		delete (window as unknown as Record<string, unknown>)[tauriInternalsKey];
	}
}

describe('syncWindowBackgroundColor', () => {
	beforeEach(() => {
		setBackgroundColor.mockReset();
		getCurrentWebviewWindow.mockClear();
		setTauri(true);
	});

	afterEach(() => {
		setTauri(false);
	});

	it('returns early when not running under Tauri', async () => {
		setTauri(false);
		await syncWindowBackgroundColor('dark');
		expect(getCurrentWebviewWindow).not.toHaveBeenCalled();
		expect(setBackgroundColor).not.toHaveBeenCalled();
	});

	it('uses the dark slate background for theme "dark"', async () => {
		setBackgroundColor.mockResolvedValue(undefined);
		await syncWindowBackgroundColor('dark');
		expect(getCurrentWebviewWindow).toHaveBeenCalledOnce();
		expect(setBackgroundColor).toHaveBeenCalledWith('#020617');
	});

	it('uses the light slate background for any other theme', async () => {
		setBackgroundColor.mockResolvedValue(undefined);
		await syncWindowBackgroundColor('light');
		expect(setBackgroundColor).toHaveBeenCalledWith('#f8fafc');
	});

	it('uses the light background for arbitrary theme strings', async () => {
		setBackgroundColor.mockResolvedValue(undefined);
		await syncWindowBackgroundColor('sepia');
		expect(setBackgroundColor).toHaveBeenCalledWith('#f8fafc');
	});

	it('swallows errors from setBackgroundColor', async () => {
		setBackgroundColor.mockRejectedValue(new Error('not supported'));
		await expect(syncWindowBackgroundColor('dark')).resolves.toBeUndefined();
	});
});
