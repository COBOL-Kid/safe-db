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

	it('renders friendly labels for builder result aliases', () => {
		const result: QueryResult = {
			columns: [
				{ name: 't0__id', data_type: 'INT' },
				{ name: 't0__first_name', data_type: 'VARCHAR' },
				{ name: 'user__defined', data_type: 'VARCHAR' }
			],
			rows: [[1, 'Ada', 'kept']],
			row_count: 1,
			truncated: false,
			warnings: []
		};

		render(ResultsTable, { result });

		const ths = document.querySelectorAll('thead th');
		expect(Array.from(ths).map((th) => th.textContent)).toEqual([
			'id',
			'first_name',
			'user__defined'
		]);
		expect(ths[0]).toHaveAttribute('title', 't0__id');
		expect(ths[1]).toHaveAttribute('title', 't0__first_name');
		expect(ths[2]).not.toHaveAttribute('title');
	});

	it('renders column headers for an empty result with known columns', () => {
		const result = makeResult([]);

		render(ResultsTable, { result });

		const ths = document.querySelectorAll('thead th');
		expect(Array.from(ths).map((th) => th.textContent)).toEqual(['a', 'b', 'c']);
		expect(screen.getByText('No rows returned.')).toBeInTheDocument();
		expect(document.querySelector('tbody td')?.getAttribute('colspan')).toBe('3');
	});

	it('renders the centered empty state when no rows or columns are known', () => {
		const result: QueryResult = {
			columns: [],
			rows: [],
			row_count: 0,
			truncated: false,
			warnings: []
		};

		render(ResultsTable, { result });

		expect(screen.getByText('No rows returned.')).toBeInTheDocument();
		expect(document.querySelector('table')).toBeNull();
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
