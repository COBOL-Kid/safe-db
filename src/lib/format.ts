import type { HistoryEntry } from './ir';

/** Format a UNIX-seconds timestamp as a short relative string.
 *
 *  - `0` / current second → `"just now"`
 *  - `< 1m`  → `"<mins>m ago"`
 *  - `< 1h`  → `"<hours>h ago"`
 *  - `< 1d`  → `"<days>d ago"`
 *  - `< 7d`  → short human form
 *  - `>= 7d` → locale-formatted date
 *  - `NaN`/empty → `""`
 *
 *  Pure function — no DOM or component state — so it can be unit-tested
 *  without rendering. The history page's relative time chip uses this
 *  directly; the Svelte 5 template was previously inlining the same logic
 *  inside the `formatTime` function.
 */
export function formatTime(ts: string, now: Date = new Date()): string {
	const sec = parseInt(ts);
	if (isNaN(sec)) return '';
	const d = new Date(sec * 1000);
	const diff = now.getTime() - d.getTime();
	const mins = Math.floor(diff / 60000);
	const hours = Math.floor(diff / 3600000);
	const days = Math.floor(diff / 86400000);
	if (mins < 1) return 'just now';
	if (mins < 60) return `${mins}m ago`;
	if (hours < 24) return `${hours}h ago`;
	if (days < 7) return `${days}d ago`;
	return d.toLocaleDateString();
}

/** Build a one-line summary of a query spec for the history list.
 *
 *  Format: `<tables> · <cols> col(s) · <joins> join(s) · limit <limit>` —
 *  column / join segments are omitted when their counts are zero.
 */
export function summarizeSpec(entry: HistoryEntry): string {
	const tables = entry.spec.tables.map((t) => t.name).join(', ');
	const cols = entry.spec.columns.length;
	const joins = entry.spec.joins.length;
	const parts = [tables];
	if (cols > 0) parts.push(`${cols} col${cols !== 1 ? 's' : ''}`);
	if (joins > 0) parts.push(`${joins} join${joins !== 1 ? 's' : ''}`);
	parts.push(`limit ${entry.spec.limit}`);
	return parts.join(' · ');
}
