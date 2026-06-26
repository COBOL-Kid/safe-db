import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/svelte';
import ResultsTable from '$lib/components/ResultsTable.svelte';
import type { QueryResult } from '$lib/ir';

describe('ResultsTable', () => {
	it('renders null and boolean cell values predictably', () => {
		const result: QueryResult = {
			columns: ['active', 'note'],
			rows: [
				[true, null],
				[false, 'ok']
			],
			row_count: 2,
			truncated: false,
			warnings: []
		};

		render(ResultsTable, { props: { result } });

		expect(screen.getByText('null')).toBeInTheDocument();
		expect(screen.getByText('true')).toBeInTheDocument();
		expect(screen.getByText('false')).toBeInTheDocument();
		expect(screen.getByText('ok')).toBeInTheDocument();
	});
});
