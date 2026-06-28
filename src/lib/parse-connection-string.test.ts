import { describe, expect, it } from 'vitest';
import { ConnectionStringParseError, parseConnectionString } from '$lib/parse-connection-string';

describe('parseConnectionString', () => {
	it('parses PostgreSQL URI with verify-full', () => {
		const parsed = parseConnectionString('postgresql://u:p%40ss@db.example.com:5432/app?sslmode=verify-full');

		expect(parsed).toMatchObject({
			dialect: 'Postgres',
			host: 'db.example.com',
			port: 5432,
			database: 'app',
			username: 'u',
			password: 'p@ss',
			inferredLocation: 'cloud',
			transport_security: { mode: 'VerifyIdentity' }
		});
		expect(parsed.sanitizedInput).not.toContain('p%40ss');
	});

	it('parses PostgreSQL JDBC with require', () => {
		const parsed = parseConnectionString('jdbc:postgresql://host:5432/db?sslmode=require');

		expect(parsed.dialect).toBe('Postgres');
		expect(parsed.transport_security.mode).toBe('EncryptOnly');
	});

	it('maps PostgreSQL sslmode=disable to disabled transport', () => {
		const parsed = parseConnectionString('postgres://u@localhost/db?sslmode=disable');

		expect(parsed.transport_security.mode).toBe('Disabled');
		expect(parsed.inferredLocation).toBe('local');
	});

	it('parses MySQL URI and preserves an empty password', () => {
		const parsed = parseConnectionString('mysql://u:@localhost:3306/db?ssl-mode=VERIFY_IDENTITY');

		expect(parsed).toMatchObject({
			dialect: 'MySql',
			host: 'localhost',
			port: 3306,
			database: 'db',
			username: 'u',
			password: '',
			inferredLocation: 'local',
			transport_security: { mode: 'VerifyIdentity' }
		});
	});

	it('warns for MySQL CA paths that cannot be read from the browser', () => {
		const parsed = parseConnectionString('jdbc:mysql://host:3306/db?ssl_ca=/tmp/ca.pem');

		expect(parsed.warnings).toEqual([
			'A CA path was included in the URL. Paste the PEM certificate after parsing.'
		]);
	});

	it('defaults MySQL local connections without SSL params to disabled transport', () => {
		const parsed = parseConnectionString('mysql://u@localhost/db');

		expect(parsed.transport_security.mode).toBe('Disabled');
	});

	it('parses SQL Server ADO.NET strings', () => {
		const parsed = parseConnectionString(
			'Server=host,1433;Database=db;User ID=u;Password={p;semi};Encrypt=True;TrustServerCertificate=False'
		);

		expect(parsed).toMatchObject({
			dialect: 'Mssql',
			host: 'host',
			port: 1433,
			database: 'db',
			username: 'u',
			password: 'p;semi',
			transport_security: { mode: 'VerifyIdentity' }
		});
		expect(parsed.sanitizedInput).toContain('Password=');
		expect(parsed.sanitizedInput).not.toContain('p;semi');
	});

	it('parses SQL Server aliases and disabled encryption', () => {
		const parsed = parseConnectionString(
			'Data Source=tcp:db.example.com,1444;Initial Catalog=warehouse;UID=readonly;PWD=p;Encrypt=no'
		);

		expect(parsed).toMatchObject({
			dialect: 'Mssql',
			host: 'db.example.com',
			port: 1444,
			database: 'warehouse',
			username: 'readonly',
			password: 'p',
			transport_security: { mode: 'Disabled' }
		});
	});

	it('parses SQL Server JDBC strings', () => {
		const parsed = parseConnectionString(
			'jdbc:sqlserver://host:1433;databaseName=db;user=u;password=p;encrypt=true;trustServerCertificate=true'
		);

		expect(parsed).toMatchObject({
			dialect: 'Mssql',
			host: 'host',
			port: 1433,
			database: 'db',
			username: 'u',
			password: 'p',
			transport_security: { mode: 'EncryptOnly' }
		});
	});

	it('parses Oracle TCPS with wallet location', () => {
		const parsed = parseConnectionString(
			'jdbc:oracle:thin:@tcps:host:1521/svc?wallet_location=/path/to/wallet'
		);

		expect(parsed).toMatchObject({
			dialect: 'Oracle',
			host: 'host',
			port: 1521,
			database: 'svc',
			transport_security: {
				mode: 'VerifyIdentity',
				oracle_wallet_location: '/path/to/wallet'
			}
		});
		expect(parsed.warnings).toEqual([]);
	});

	it('parses Oracle user/password Easy Connect and sanitizes the password', () => {
		const parsed = parseConnectionString('jdbc:oracle:thin:user/p%40ss@//host:1521/svc');

		expect(parsed).toMatchObject({
			dialect: 'Oracle',
			host: 'host',
			port: 1521,
			database: 'svc',
			username: 'user',
			password: 'p@ss',
			transport_security: { mode: 'Disabled' }
		});
		expect(parsed.sanitizedInput).toBe('jdbc:oracle:thin:user/@//host:1521/svc');
	});

	it('parses Oracle plain Easy Connect', () => {
		const parsed = parseConnectionString('jdbc:oracle:thin:@//host:1521/svc');

		expect(parsed).toMatchObject({
			dialect: 'Oracle',
			host: 'host',
			port: 1521,
			database: 'svc',
			transport_security: { mode: 'Disabled' }
		});
	});

	it('parses Oracle thin host/service form as plain TCP unless TCPS is explicit', () => {
		const parsed = parseConnectionString('jdbc:oracle:thin:@host:1521/svc');

		expect(parsed).toMatchObject({
			dialect: 'Oracle',
			host: 'host',
			port: 1521,
			database: 'svc',
			transport_security: { mode: 'Disabled' }
		});
		expect(parsed.warnings).toEqual([]);
	});

	it('warns when Oracle TCPS has no wallet', () => {
		const parsed = parseConnectionString('jdbc:oracle:thin:@tcps:host:1521/svc');

		expect(parsed.warnings).toEqual([
			'Oracle TCPS requires a wallet location before testing or saving.'
		]);
	});

	it('fails on malformed input with a structured error', () => {
		expect(() => parseConnectionString('not a connection string')).toThrow(
			ConnectionStringParseError
		);
	});

	it('rejects unsupported Oracle TNS description blocks', () => {
		expect(() =>
			parseConnectionString(
				'jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=tcps)(HOST=host)(PORT=1521)))'
			)
		).toThrow('Oracle TNS DESCRIPTION blocks are not supported');
	});
});
