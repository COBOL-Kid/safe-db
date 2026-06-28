/** Separator between table alias and column name in selected-column keys. */
export const COLUMN_KEY_SEP = '\0';

export function columnKey(alias: string, column: string): string {
	return `${alias}${COLUMN_KEY_SEP}${column}`;
}

export function parseColumnKey(key: string): { alias: string; column: string } {
	const sep = key.indexOf(COLUMN_KEY_SEP);
	if (sep !== -1) {
		return { alias: key.slice(0, sep), column: key.slice(sep + 1) };
	}
	// Legacy keys used a single dot between alias and column.
	const dot = key.indexOf('.');
	if (dot === -1) {
		return { alias: key, column: '' };
	}
	return { alias: key.slice(0, dot), column: key.slice(dot + 1) };
}

export function columnKeyPrefix(alias: string): string {
	return `${alias}${COLUMN_KEY_SEP}`;
}
