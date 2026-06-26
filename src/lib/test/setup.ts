import '@testing-library/jest-dom/vitest';
import { vi } from 'vitest';

vi.mock('@tauri-apps/api/core', () => ({
	invoke: vi.fn()
}));

vi.mock('$app/environment', () => ({
	browser: true,
	dev: true,
	building: false,
	version: 'test'
}));

vi.mock('$app/navigation', () => ({
	goto: vi.fn(),
	beforeNavigate: vi.fn(),
	afterNavigate: vi.fn()
}));

if (!globalThis.crypto?.randomUUID) {
	Object.defineProperty(globalThis, 'crypto', {
		value: {
			randomUUID: () => '00000000-0000-4000-8000-000000000001'
		},
		configurable: true
	});
}
