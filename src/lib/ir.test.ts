import { describe, expect, it } from 'vitest';
import {
	CURRENT_SCHEMA_VERSION,
	DEFAULT_LIMIT,
	MAX_LIMIT,
	classifyColumn,
	defaultFilterGroup,
	literalKindForColumn,
	makeFilter,
	makeLiteral,
	newNodeId,
	opsForColumn,
	qualifiedName,
	valueKindForOp
} from '$lib/ir';
import type { FilterOp, TableInfo } from '$lib/ir';

describe('ir', () => {
	it('qualifiedName includes schema when present', () => {
		const table: TableInfo = {
			schema: 'public',
			name: 'users',
			columns: [],
			indexes: []
		};
		expect(qualifiedName(table)).toBe('public.users');
	});

	it('qualifiedName omits empty schema', () => {
		const table: TableInfo = {
			schema: '',
			name: 'users',
			columns: [],
			indexes: []
		};
		expect(qualifiedName(table)).toBe('users');
	});

	it('qualifiedName preserves dots and quotes in identifiers', () => {
		const table: TableInfo = {
			schema: 'weird.schema',
			name: 'orders"v2',
			columns: [],
			indexes: []
		};
		expect(qualifiedName(table)).toBe('weird.schema.orders"v2');
	});

	it('exports limit constants used by query store', () => {
		expect(DEFAULT_LIMIT).toBe(100);
		expect(MAX_LIMIT).toBe(1000);
		expect(CURRENT_SCHEMA_VERSION).toBe(2);
	});

	describe('valueKindForOp', () => {
		it('returns None for null/empty ops', () => {
			expect(valueKindForOp('IsNull')).toBe('None');
			expect(valueKindForOp('IsNotNull')).toBe('None');
			expect(valueKindForOp('IsEmpty')).toBe('None');
			expect(valueKindForOp('IsNotEmpty')).toBe('None');
		});

		it('returns List for in/notIn ops', () => {
			expect(valueKindForOp('In')).toBe('List');
			expect(valueKindForOp('NotIn')).toBe('List');
		});

		it('returns Pair for between', () => {
			expect(valueKindForOp('Between')).toBe('Pair');
		});

		it('returns Single for everything else', () => {
			const singles: FilterOp[] = [
				'Eq',
				'Ne',
				'Gt',
				'Gte',
				'Lt',
				'Lte',
				'Like',
				'NotLike',
				'Ilike'
			];
			for (const op of singles) {
				expect(valueKindForOp(op)).toBe('Single');
			}
		});
	});

	describe('classifyColumn', () => {
		it.each([
			['bool', 'Bool'],
			['BOOLEAN', 'Bool'],
			['bit', 'Bool'],
			['date', 'Date']
		])('classifies %s as %s', (dt, expected) => {
			expect(classifyColumn(dt)).toBe(expected);
		});

		it('classifies timestamps and datetimes as DateTime', () => {
			expect(classifyColumn('timestamp')).toBe('DateTime');
			expect(classifyColumn('TIMESTAMP WITH TIME ZONE')).toBe('DateTime');
			expect(classifyColumn('datetime')).toBe('DateTime');
			expect(classifyColumn('datetime2')).toBe('DateTime');
			expect(classifyColumn('smalldatetime')).toBe('DateTime');
		});

		it.each([
			['int', 'Numeric'],
			['INTEGER', 'Numeric'],
			['bigint', 'Numeric'],
			['numeric(10,2)', 'Numeric'],
			['decimal(18,4)', 'Numeric'],
			['real', 'Numeric'],
			['double precision', 'Numeric'],
			['money', 'Numeric']
		])('classifies %s as %s', (dt, expected) => {
			expect(classifyColumn(dt)).toBe(expected);
		});

		it.each([
			['text', 'Text'],
			['varchar(255)', 'Text'],
			['VARCHAR2', 'Text'],
			['nvarchar(50)', 'Text'],
			['char(2)', 'Text'],
			['clob', 'Text']
		])('classifies %s as %s', (dt, expected) => {
			expect(classifyColumn(dt)).toBe(expected);
		});

		it('returns Other for unknown types', () => {
			expect(classifyColumn('uuid')).toBe('Other');
			expect(classifyColumn('jsonb')).toBe('Other');
		});
	});

	describe('literalKindForColumn', () => {
		it('maps to Int for numeric, Bool, Date, DateTime, Text for everything else', () => {
			expect(literalKindForColumn('int')).toBe('Int');
			expect(literalKindForColumn('bool')).toBe('Bool');
			expect(literalKindForColumn('date')).toBe('Date');
			expect(literalKindForColumn('timestamp')).toBe('DateTime');
			expect(literalKindForColumn('varchar')).toBe('Text');
			expect(literalKindForColumn('uuid')).toBe('Text');
		});
	});

	describe('opsForColumn', () => {
		it('exposes Like/Ilike only for text', () => {
			const ops = opsForColumn('varchar');
			expect(ops).toContain('Like');
			expect(ops).toContain('Ilike');
			expect(ops).toContain('IsEmpty');
		});

		it('exposes Between only for numeric/date/datetime', () => {
			expect(opsForColumn('int')).toContain('Between');
			expect(opsForColumn('date')).toContain('Between');
			expect(opsForColumn('varchar')).not.toContain('Between');
			expect(opsForColumn('bool')).not.toContain('Between');
		});

		it('restricts bool to Eq/Ne/IsNull/IsNotNull', () => {
			expect(opsForColumn('bool')).toEqual(['Eq', 'Ne', 'IsNull', 'IsNotNull']);
		});

		it('falls back to Eq/Ne/IsNull/IsNotNull for unknown types', () => {
			expect(opsForColumn('uuid')).toEqual(['Eq', 'Ne', 'IsNull', 'IsNotNull']);
		});
	});

	describe('factory helpers', () => {
		it('makeLiteral uses the column data type', () => {
			expect(makeLiteral('int', '42')).toEqual({ kind: 'Int', text: '42' });
			expect(makeLiteral('bool', 'true')).toEqual({ kind: 'Bool', text: 'true' });
			expect(makeLiteral('varchar', 'hi')).toEqual({ kind: 'Text', text: 'hi' });
		});

		it('makeFilter builds a Single for a comparison op', () => {
			const f = makeFilter('t0', 'name', 'varchar', 'Eq', 'Alice');
			expect(f.table_alias).toBe('t0');
			expect(f.column).toBe('name');
			expect(f.op).toBe('Eq');
			expect(f.value).toEqual({ Single: { kind: 'Text', text: 'Alice' } });
			expect(f.id).toBeDefined();
		});

		it('makeFilter builds a Pair for Between', () => {
			const f = makeFilter('t0', 'age', 'int', 'Between', '');
			expect(f.value).toEqual({
				Pair: [
					{ kind: 'Int', text: '' },
					{ kind: 'Int', text: '' }
				]
			});
		});

		it('makeFilter builds a List for In with empty text starting empty', () => {
			const f = makeFilter('t0', 'id', 'int', 'In', '');
			expect(f.value).toEqual({ List: [] });
		});

		it('makeFilter builds a List for In with one element when text is set', () => {
			const f = makeFilter('t0', 'id', 'int', 'In', '7');
			expect(f.value).toEqual({ List: [{ kind: 'Int', text: '7' }] });
		});

		it('makeFilter sets value to null for no-value ops', () => {
			const f = makeFilter('t0', 'deleted_at', 'timestamp', 'IsNull', '');
			expect(f.value).toBeNull();
		});

		it('defaultFilterGroup returns a group with a unique id and no children', () => {
			const a = defaultFilterGroup();
			const b = defaultFilterGroup();
			expect(a.connector).toBe('And');
			expect(a.children).toEqual([]);
			expect(typeof a.id).toBe('string');
			expect(a.id).not.toBe('');
			expect(a.id).not.toBe(b.id);
		});

		it('newNodeId returns unique values', () => {
			expect(newNodeId()).not.toBe(newNodeId());
		});
	});
});
