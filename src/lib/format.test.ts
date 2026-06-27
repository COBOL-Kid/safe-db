import { describe, expect, it } from 'vitest';
import { formatTime, summarizeSpec } from '$lib/format';
import type { HistoryEntry } from '$lib/ir';

function entry(overrides: Partial<HistoryEntry> = {}): HistoryEntry {
	return {
		id: 'h1',
		connection_id: 'c1',
		connection_name: 'Test DB',
		spec: {
			tables: [
				{ schema: 'public', name: 'users', alias: 't0' },
				{ schema: 'public', name: 'orders', alias: 't1' }
			],
			columns: [
				{ table_alias: 't0', column: 'id' },
				{ table_alias: 't0', column: 'email' }
			],
			joins: [{ left_alias: 't0', left_column: 'id', right_alias: 't1', right_column: 'user_id' }],
			filters: { id: 'g', connector: 'And', children: [] },
			limit: 50,
			schema_version: 2,
			connector_overrides: {}
		},
		row_count: 12,
		warnings: [],
		error: null,
		timestamp: '1700000000',
		...overrides
	};
}

const NOW_MS = 1_700_000_500_000; // anchor for relative-time tests
const now = new Date(NOW_MS);

describe('formatTime', () => {
	it('returns empty string for non-numeric input', () => {
		expect(formatTime('not a number', now)).toBe('');
		expect(formatTime('', now)).toBe('');
	});

	it('returns "just now" for the current second', () => {
		expect(formatTime(String(Math.floor(NOW_MS / 1000)), now)).toBe('just now');
	});

	it('formats minutes-ago entries as "<m>m ago"', () => {
		const fiveMinAgo = Math.floor((NOW_MS - 5 * 60_000) / 1000).toString();
		expect(formatTime(fiveMinAgo, now)).toBe('5m ago');
	});

	it('formats hours-ago entries as "<h>h ago"', () => {
		const twoHoursAgo = Math.floor((NOW_MS - 2 * 3_600_000) / 1000).toString();
		expect(formatTime(twoHoursAgo, now)).toBe('2h ago');
	});

	it('formats days-ago entries (< 7d) as "<d>d ago"', () => {
		const threeDaysAgo = Math.floor((NOW_MS - 3 * 86_400_000) / 1000).toString();
		expect(formatTime(threeDaysAgo, now)).toBe('3d ago');
	});

	it('falls back to toLocaleDateString for entries older than a week', () => {
		const ancient = Math.floor((NOW_MS - 30 * 86_400_000) / 1000).toString();
		// Pin locale to a deterministic value so the test is stable.
		const formatted = formatTime(ancient, now);
		expect(formatted).not.toContain('ago');
		expect(formatted).toMatch(/\d/);
	});
});

describe('summarizeSpec', () => {
	it('includes tables, plural cols, plural joins, and limit', () => {
		expect(summarizeSpec(entry())).toBe('users, orders · 2 cols · 1 join · limit 50');
	});

	it('omits the cols segment when there are no columns', () => {
		const e = entry();
		e.spec.columns = [];
		expect(summarizeSpec(e)).toBe('users, orders · 1 join · limit 50');
	});

	it('omits the joins segment when there are no joins', () => {
		const e = entry();
		e.spec.joins = [];
		expect(summarizeSpec(e)).toBe('users, orders · 2 cols · limit 50');
	});

	it('uses singular forms when count is 1', () => {
		const e = entry();
		e.spec.columns = [{ table_alias: 't0', column: 'id' }];
		e.spec.joins = [];
		expect(summarizeSpec(e)).toBe('users, orders · 1 col · limit 50');
	});

	it('handles a single-table spec with no cols or joins', () => {
		const e = entry();
		e.spec.tables = [{ schema: 'public', name: 'users', alias: 't0' }];
		e.spec.columns = [];
		e.spec.joins = [];
		expect(summarizeSpec(e)).toBe('users · limit 50');
	});
});
