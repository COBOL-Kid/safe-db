import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/svelte';
import ResultsTable from '$lib/components/ResultsTable.svelte';
import type { QueryResult } from '$lib/ir';

function makeResult(rows: QueryResult['rows'], truncated = false): QueryResult {
	return {
		columns: ['a', 'b', 'c'],
		rows,
		row_count: rows.length,
		truncated,
		warnings: []
	};
}

describe('ResultsTable', () => {
	afterEach(() => cleanup());

	it('renders null and boolean cell values predictably', () => {
		const result = makeResult([
			[true, null, 'ok'],
			[false, 'note', null]
		]);

		render(ResultsTable, { result });

		// Two null cells in the fixture, both rendered.
		expect(screen.getAllByText('null').length).toBe(2);
		expect(screen.getByText('true')).toBeInTheDocument();
		expect(screen.getByText('false')).toBeInTheDocument();
		expect(screen.getByText('ok')).toBeInTheDocument();
	});

	it('renders numeric cells as their string form', () => {
		const result = makeResult([
			[1, 2.5, 0],
			[-3, 1e9, 42]
		]);

		render(ResultsTable, { result });

		for (const text of ['1', '2.5', '0', '-3', '1000000000', '42']) {
			expect(screen.getAllByText(text).length).toBeGreaterThan(0);
		}
	});

	it('renders long strings verbatim (no truncation in the cell)', () => {
		const long = 'x'.repeat(2000);
		const result = makeResult([[long, 'short', 'tail']]);

		render(ResultsTable, { result });

		expect(screen.getByText(long)).toBeInTheDocument();
	});

	it('renders objects and arrays via String() coercion (pin current behavior)', () => {
		// The component uses String(value) for non-boolean values, which means
		// objects become "[object Object]" and arrays become comma-joined.
		// Pin this so any future change to formatCell is intentional.
		const result = makeResult([[{ foo: 'bar' }, [1, 2, 3], 'plain']]);

		render(ResultsTable, { result });

		expect(screen.getByText('[object Object]')).toBeInTheDocument();
		expect(screen.getByText('1,2,3')).toBeInTheDocument();
		expect(screen.getByText('plain')).toBeInTheDocument();
	});

	it('renders the column header row with the column names', () => {
		const result = makeResult([['x', 'y', 'z']]);

		render(ResultsTable, { result });

		// `columnheader` matches both <th> and <td> in jsdom, so filter to
		// <th> for the actual header row.
		const ths = document.querySelectorAll('thead th');
		expect(Array.from(ths).map((th) => th.textContent)).toEqual(['a', 'b', 'c']);
	});

	it('renders the empty result (no rows) without crashing', () => {
		const result = makeResult([]);

		render(ResultsTable, { result });

		// No <tbody> rendered for empty results — the component shows
		// "No rows returned." instead.
		expect(screen.getByText('No rows returned.')).toBeInTheDocument();
		expect(document.querySelectorAll('tbody tr').length).toBe(0);
	});

	it('renders the truncated badge when result.truncated is true', () => {
		const result = makeResult([['x', 'y', 'z']], true);

		render(ResultsTable, { result });

		expect(screen.getByText('Truncated')).toBeInTheDocument();
	});

	it('renders the warnings list when result.warnings is non-empty', () => {
		const result: QueryResult = {
			columns: ['a'],
			rows: [['x']],
			row_count: 1,
			truncated: false,
			warnings: ['Estimated query cost (250000) exceeds threshold (100000)']
		};

		render(ResultsTable, { result });

		expect(
			screen.getByText('⚠ Estimated query cost (250000) exceeds threshold (100000)')
		).toBeInTheDocument();
	});
});
