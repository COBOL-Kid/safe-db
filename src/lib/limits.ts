import { MAX_LIMIT } from './ir';

/** Keep in sync with `MAX_FILTER_DEPTH` in `src-tauri/src/query/validate.rs`. */
export const MAX_FILTER_DEPTH = 5;

/** Keep in sync with `MAX_IN_LIST_SIZE` in `src-tauri/src/query/validate.rs`. */
export const MAX_IN_LIST_SIZE = 1000;

/** Prefix returned when the backend cost guard blocks a query. */
export const COST_GUARD_PREFIX = 'COST_GUARD_BLOCKED:';

/** Clamp a user-entered limit to the allowed `[1, MAX_LIMIT]` range.
 *
 *  Mirrors the behavior the builder page applies to its `<input type="number">`
 *  oninput handler: `parseInt` is used (so `"25abc"` becomes `25`), `NaN` and
 *  zero fall through to `1`, and values above `MAX_LIMIT` are clipped to
 *  `MAX_LIMIT`. The store still initializes to its own default on its own.
 */
export function parseLimit(raw: string | number): number {
	const n = typeof raw === 'number' ? raw : parseInt(raw);
	if (isNaN(n) || n < 1) return 1;
	if (n > MAX_LIMIT) return MAX_LIMIT;
	return n;
}
