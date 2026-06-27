import '@testing-library/jest-dom/vitest';
import { vi } from 'vitest';

vi.mock('@tauri-apps/api/core', () => ({
	invoke: vi.fn()
}));

vi.mock('@tauri-apps/api/webviewWindow', () => ({
	getCurrentWebviewWindow: vi.fn(() => ({
		setBackgroundColor: vi.fn().mockResolvedValue(undefined)
	}))
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

// Always stub `crypto.randomUUID` so test UUIDs are deterministic across
// hosts (Node 18+ ships a native one, but we want predictable IDs in
// logs/snapshots). Each call returns a unique counter-suffixed UUID
// sharing a common prefix for log grouping.
const STABLE_UUID = '00000000-0000-4000-8000-000000000001';
let uuidCounter = 0;
const stableRandomUUID = () => {
	uuidCounter += 1;
	// Embed the counter in the last 12 hex chars for easy debugging.
	const tail = uuidCounter.toString(16).padStart(12, '0');
	return `${STABLE_UUID.slice(0, -12)}${tail}`;
};

if (!globalThis.crypto?.randomUUID) {
	Object.defineProperty(globalThis, 'crypto', {
		value: { randomUUID: stableRandomUUID },
		configurable: true,
		writable: true
	});
} else {
	Object.defineProperty(globalThis.crypto, 'randomUUID', {
		value: stableRandomUUID,
		configurable: true,
		writable: true
	});
}
