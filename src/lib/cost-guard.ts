export interface CostGuardDialogCopy {
	title: string;
	message: string;
	confirmLabel: string;
}

export function costGuardDialogCopy(reason: string | null): CostGuardDialogCopy {
	const normalized = reason ?? '';
	const highCost = normalized.includes('Estimated query cost exceeds threshold');

	if (highCost) {
		return {
			title: 'This query may scan more data than expected',
			message:
				'Safe DB estimated this query may be expensive. It will still be limited and stopped if it runs too long.',
			confirmLabel: 'Run with safeguards'
		};
	}

	return {
		title: 'Safe DB could not preview this query',
		message:
			'The database did not return a usable estimate. The query will still run with Safe DB protections: read-only access, a row limit, and a timeout.',
		confirmLabel: 'Run with safeguards'
	};
}
