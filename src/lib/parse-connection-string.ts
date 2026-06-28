import {
	inferLocation,
	isLocalHost,
	type DatabaseLocation
} from '$lib/connection-presets';
import type { Dialect, TransportSecurity, TransportSecurityMode } from '$lib/ir';

export type ParsedConnection = {
	dialect: Dialect;
	host: string;
	port: number;
	database: string;
	username: string;
	password: string | null;
	transport_security: TransportSecurity;
	inferredLocation: Exclude<DatabaseLocation, 'organization'>;
	warnings: string[];
	sanitizedInput: string;
};

export class ConnectionStringParseError extends Error {
	constructor(message: string) {
		super(message);
		this.name = 'ConnectionStringParseError';
	}
}

type MutableTransport = {
	mode: TransportSecurityMode;
	ca_pem?: string | null;
	oracle_wallet_location?: string | null;
};

const DEFAULT_PORTS: Record<Dialect, number> = {
	Postgres: 5432,
	MySql: 3306,
	Mssql: 1433,
	Oracle: 1521
};

export function parseConnectionString(input: string): ParsedConnection {
	const raw = input.trim();
	if (!raw) throw new ConnectionStringParseError('Paste a connection string to continue.');

	if (/^jdbc:postgresql:\/\//i.test(raw)) return parsePostgresJdbc(raw);
	if (/^postgres(?:ql)?:\/\//i.test(raw)) return parsePostgresUri(raw);
	if (/^jdbc:mysql:\/\//i.test(raw)) return parseMysqlJdbc(raw);
	if (/^mysql:\/\//i.test(raw)) return parseMysqlUri(raw);
	if (/^jdbc:sqlserver:\/\//i.test(raw)) return parseSqlServerJdbc(raw);
	if (/^jdbc:oracle:thin:/i.test(raw) || /^@?tcps?:/i.test(raw) || /^@\/\//.test(raw)) {
		return parseOracle(raw);
	}
	if (looksLikeSqlServerKeyValue(raw)) return parseSqlServerKeyValue(raw);

	throw new ConnectionStringParseError(
		'This connection string format is not recognized. Try the guided setup instead.'
	);
}

function baseResult(args: {
	dialect: Dialect;
	host: string;
	port?: number;
	database: string;
	username?: string;
	password?: string | null;
	transport: MutableTransport;
	warnings?: string[];
	sanitizedInput: string;
}): ParsedConnection {
	const host = stripBrackets(args.host.trim());
	if (!host) throw new ConnectionStringParseError('Connection string is missing a host.');
	if (!args.database.trim()) {
		throw new ConnectionStringParseError('Connection string is missing a database name.');
	}

	const port = args.port ?? DEFAULT_PORTS[args.dialect];
	if (!Number.isFinite(port) || port < 1 || port > 65535) {
		throw new ConnectionStringParseError('Connection string has an invalid port.');
	}

	return {
		dialect: args.dialect,
		host,
		port,
		database: args.database.trim(),
		username: args.username?.trim() ?? '',
		password: args.password ?? null,
		transport_security: {
			mode: args.transport.mode,
			ca_pem: args.transport.ca_pem ?? null,
			oracle_wallet_location: args.transport.oracle_wallet_location ?? null,
			legacy_implicit: false
		},
		inferredLocation: inferLocation(host),
		warnings: args.warnings ?? [],
		sanitizedInput: args.sanitizedInput
	};
}

function parsePostgresUri(raw: string): ParsedConnection {
	const url = parseUrl(raw, 'PostgreSQL');
	const sslmode = lowercaseParam(url.searchParams, 'sslmode');
	return baseResult({
		dialect: 'Postgres',
		host: url.hostname,
		port: parsePort(url.port, 'Postgres'),
		database: pathnameDatabase(url.pathname),
		username: decodeURIComponent(url.username),
		password: passwordFromUrl(raw, url),
		transport: postgresTransport(sslmode, url.hostname),
		sanitizedInput: sanitizeUrlPassword(url)
	});
}

function parsePostgresJdbc(raw: string): ParsedConnection {
	return parsePostgresUri(raw.replace(/^jdbc:/i, ''));
}

function parseMysqlUri(raw: string): ParsedConnection {
	const url = parseUrl(raw, 'MySQL');
	const sslMode = lowercaseParam(url.searchParams, 'ssl-mode') ?? lowercaseParam(url.searchParams, 'ssl_mode') ?? lowercaseParam(url.searchParams, 'sslMode');
	const warnings = sslCaWarnings(url.searchParams);
	return baseResult({
		dialect: 'MySql',
		host: url.hostname,
		port: parsePort(url.port, 'MySql'),
		database: pathnameDatabase(url.pathname),
		username: decodeURIComponent(url.username),
		password: passwordFromUrl(raw, url),
		transport: mysqlTransport(sslMode, url.hostname),
		warnings,
		sanitizedInput: sanitizeUrlPassword(url)
	});
}

function parseMysqlJdbc(raw: string): ParsedConnection {
	return parseMysqlUri(raw.replace(/^jdbc:/i, ''));
}

function parseSqlServerJdbc(raw: string): ParsedConnection {
	const rest = raw.replace(/^jdbc:sqlserver:\/\//i, '');
	const [serverPart, ...propertyParts] = splitSemicolonRecords(rest);
	const { host, port } = parseSqlServerHost(serverPart);
	const props = parseSemicolonKeyValues(propertyParts.join(';'));
	const database = findKey(props, ['databasename', 'database', 'initial catalog']) ?? '';
	const username = findKey(props, ['user', 'username', 'user id', 'uid']) ?? '';
	const password = findKey(props, ['password', 'pwd']);
	const encrypt = findKey(props, ['encrypt']);
	const trustServerCertificate = findKey(props, ['trustservercertificate', 'trust server certificate']);

	return baseResult({
		dialect: 'Mssql',
		host,
		port,
		database,
		username,
		password: password ?? null,
		transport: sqlServerTransport(encrypt, trustServerCertificate, host),
		sanitizedInput: sanitizeSqlServerKeyValue(raw)
	});
}

function parseSqlServerKeyValue(raw: string): ParsedConnection {
	const props = parseSemicolonKeyValues(raw);
	const server = findKey(props, ['server', 'data source', 'address', 'addr', 'network address']) ?? '';
	const { host, port } = parseSqlServerHost(server);
	const database = findKey(props, ['database', 'initial catalog']) ?? '';
	const username = findKey(props, ['user id', 'uid', 'user', 'username']) ?? '';
	const password = findKey(props, ['password', 'pwd']);
	const encrypt = findKey(props, ['encrypt']);
	const trustServerCertificate = findKey(props, ['trustservercertificate', 'trust server certificate']);

	return baseResult({
		dialect: 'Mssql',
		host,
		port,
		database,
		username,
		password: password ?? null,
		transport: sqlServerTransport(encrypt, trustServerCertificate, host),
		sanitizedInput: sanitizeSqlServerKeyValue(raw)
	});
}

function parseOracle(raw: string): ParsedConnection {
	let rest = raw.replace(/^jdbc:oracle:thin:/i, '');
	let username = '';
	let password: string | null = null;

	if (!rest.startsWith('@')) {
		const atIndex = findOracleAuthSeparator(rest);
		if (atIndex > -1) {
			const auth = rest.slice(0, atIndex);
			rest = rest.slice(atIndex);
			const slashIndex = auth.indexOf('/');
			if (slashIndex > -1) {
				username = auth.slice(0, slashIndex);
				password = auth.slice(slashIndex + 1);
			}
		}
	}

	rest = rest.replace(/^@/, '');
	if (/^\(description=/i.test(rest)) {
		throw new ConnectionStringParseError(
			'Oracle TNS DESCRIPTION blocks are not supported. Use guided setup or paste an Easy Connect URL.'
		);
	}

	const protocol = /^tcps:/i.test(rest) ? 'tcps' : 'tcp';
	rest = rest.replace(/^tcps?:/i, '').replace(/^\/\//, '');

	const pseudoUrl = parseUrl(`${protocol}://${rest}`, 'Oracle');
	const walletLocation =
		paramValue(pseudoUrl.searchParams, 'wallet_location') ??
		paramValue(pseudoUrl.searchParams, 'walletLocation');
	const mode: TransportSecurityMode = protocol === 'tcps' ? 'VerifyIdentity' : 'Disabled';
	const warnings =
		mode !== 'Disabled' && !walletLocation
			? ['Oracle TCPS requires a wallet location before testing or saving.']
			: [];

	return baseResult({
		dialect: 'Oracle',
		host: pseudoUrl.hostname,
		port: parsePort(pseudoUrl.port, 'Oracle'),
		database: pathnameDatabase(pseudoUrl.pathname),
		username: decodeURIComponent(username),
		password: password === null ? null : decodeURIComponent(password),
		transport: {
			mode,
			oracle_wallet_location: walletLocation ?? null
		},
		warnings,
		sanitizedInput: sanitizeOracleInput(raw)
	});
}

function parseUrl(raw: string, label: string): URL {
	try {
		return new URL(raw);
	} catch {
		throw new ConnectionStringParseError(`${label} connection string is not a valid URL.`);
	}
}

function pathnameDatabase(pathname: string): string {
	return decodeURIComponent(pathname.replace(/^\/+/, '').split('/')[0] ?? '');
}

function parsePort(port: string, dialect: Dialect): number {
	return port ? Number(port) : DEFAULT_PORTS[dialect];
}

function postgresTransport(sslmode: string | null, host: string): MutableTransport {
	switch (sslmode) {
		case 'disable':
			return { mode: 'Disabled' };
		case 'require':
			return { mode: 'EncryptOnly' };
		case 'verify-ca':
			return { mode: 'VerifyCa' };
		case 'verify-full':
			return { mode: 'VerifyIdentity' };
		default:
			return { mode: isLocalHost(host) ? 'Disabled' : 'VerifyIdentity' };
	}
}

function mysqlTransport(sslMode: string | null, host: string): MutableTransport {
	switch (sslMode?.replace(/-/g, '_')) {
		case 'disabled':
			return { mode: 'Disabled' };
		case 'required':
			return { mode: 'EncryptOnly' };
		case 'verify_ca':
			return { mode: 'VerifyCa' };
		case 'verify_identity':
			return { mode: 'VerifyIdentity' };
		default:
			return { mode: isLocalHost(host) ? 'Disabled' : 'VerifyIdentity' };
	}
}

function sqlServerTransport(
	encrypt: string | undefined,
	trustServerCertificate: string | undefined,
	host: string
): MutableTransport {
	if (isFalse(encrypt)) return { mode: 'Disabled' };
	if (isTrue(trustServerCertificate)) return { mode: 'EncryptOnly' };
	if (isTrue(encrypt)) return { mode: 'VerifyIdentity' };
	return { mode: isLocalHost(host) ? 'Disabled' : 'VerifyIdentity' };
}

function isTrue(value: string | undefined): boolean {
	return ['true', 'yes', 'mandatory'].includes(value?.trim().toLowerCase() ?? '');
}

function isFalse(value: string | undefined): boolean {
	return ['false', 'no', 'optional'].includes(value?.trim().toLowerCase() ?? '');
}

function lowercaseParam(params: URLSearchParams, key: string): string | null {
	return paramValue(params, key)?.toLowerCase() ?? null;
}

function paramValue(params: URLSearchParams, key: string): string | null {
	for (const [candidate, value] of params.entries()) {
		if (candidate.toLowerCase() === key.toLowerCase()) return value;
	}
	return null;
}

function sslCaWarnings(params: URLSearchParams): string[] {
	return paramValue(params, 'ssl-ca') || paramValue(params, 'ssl_ca')
		? ['A CA path was included in the URL. Paste the PEM certificate after parsing.']
		: [];
}

function looksLikeSqlServerKeyValue(raw: string): boolean {
	return /(^|;)\s*(server|data source|database|initial catalog|user id|uid)\s*=/i.test(raw);
}

function splitSemicolonRecords(raw: string): string[] {
	const records: string[] = [];
	let current = '';
	let quote: string | null = null;
	let braceDepth = 0;

	for (const char of raw) {
		if (quote) {
			current += char;
			if (char === quote) quote = null;
			continue;
		}
		if (char === "'" || char === '"') {
			quote = char;
			current += char;
			continue;
		}
		if (char === '{') {
			braceDepth += 1;
			current += char;
			continue;
		}
		if (char === '}') {
			braceDepth = Math.max(0, braceDepth - 1);
			current += char;
			continue;
		}
		if (char === ';' && braceDepth === 0) {
			records.push(current);
			current = '';
			continue;
		}
		current += char;
	}

	if (current) records.push(current);
	return records.filter((record) => record.trim().length > 0);
}

function parseSemicolonKeyValues(raw: string): Map<string, string> {
	const props = new Map<string, string>();
	for (const record of splitSemicolonRecords(raw)) {
		const eqIndex = record.indexOf('=');
		if (eqIndex < 0) continue;
		const key = record.slice(0, eqIndex).trim().toLowerCase();
		const value = unwrapValue(record.slice(eqIndex + 1).trim());
		props.set(key, value);
	}
	return props;
}

function unwrapValue(value: string): string {
	if (value.startsWith('{') && value.endsWith('}')) return value.slice(1, -1);
	if (
		(value.startsWith('"') && value.endsWith('"')) ||
		(value.startsWith("'") && value.endsWith("'"))
	) {
		return value.slice(1, -1);
	}
	return value;
}

function findKey(props: Map<string, string>, keys: string[]): string | undefined {
	for (const key of keys) {
		const value = props.get(key.toLowerCase());
		if (value !== undefined) return value;
	}
	return undefined;
}

function parseSqlServerHost(value: string): { host: string; port?: number } {
	let server = unwrapValue(value.trim());
	server = server.replace(/^tcp:/i, '');
	if (!server) throw new ConnectionStringParseError('SQL Server connection string is missing a server.');

	if (server.startsWith('[')) {
		const close = server.indexOf(']');
		const host = server.slice(1, close);
		const port = server.slice(close + 1).replace(/^[:,]/, '');
		return { host, port: port ? Number(port) : undefined };
	}

	const [host, port] = server.includes(',') ? server.split(',', 2) : server.split(':', 2);
	return { host, port: port ? Number(port) : undefined };
}

function sanitizeUrlPassword(url: URL): string {
	const clean = new URL(url.toString());
	if (clean.password) clean.password = '';
	return clean.toString();
}

function passwordFromUrl(raw: string, url: URL): string | null {
	const schemeEnd = raw.indexOf('://');
	if (schemeEnd < 0) return null;
	const authorityStart = schemeEnd + 3;
	const authorityEndCandidates = ['/', '?', '#']
		.map((char) => raw.indexOf(char, authorityStart))
		.filter((index) => index >= 0);
	const authorityEnd =
		authorityEndCandidates.length > 0 ? Math.min(...authorityEndCandidates) : raw.length;
	const authority = raw.slice(authorityStart, authorityEnd);
	const atIndex = authority.lastIndexOf('@');
	if (atIndex < 0) return null;
	const auth = authority.slice(0, atIndex);
	if (!auth.includes(':')) return null;
	return decodeURIComponent(url.password);
}

function sanitizeSqlServerKeyValue(raw: string): string {
	return splitSemicolonRecords(raw)
		.map((record) => {
			const eqIndex = record.indexOf('=');
			if (eqIndex < 0) return record;
			const key = record.slice(0, eqIndex).trim();
			if (['password', 'pwd'].includes(key.toLowerCase())) return `${key}=`;
			return record;
		})
		.join(';');
}

function sanitizeOracleInput(raw: string): string {
	const prefixMatch = raw.match(/^jdbc:oracle:thin:/i);
	const prefix = prefixMatch?.[0] ?? '';
	const rest = raw.slice(prefix.length);
	if (rest.startsWith('@')) return raw;

	const atIndex = findOracleAuthSeparator(rest);
	if (atIndex < 0) return raw;

	const auth = rest.slice(0, atIndex);
	const slashIndex = auth.indexOf('/');
	if (slashIndex < 0) return raw;

	return `${prefix}${auth.slice(0, slashIndex)}/${rest.slice(atIndex)}`;
}

function findOracleAuthSeparator(rest: string): number {
	const queryStart = rest.search(/[?#]/);
	const authAndConnect = queryStart < 0 ? rest : rest.slice(0, queryStart);
	return authAndConnect.lastIndexOf('@');
}

function stripBrackets(host: string): string {
	return host.startsWith('[') && host.endsWith(']') ? host.slice(1, -1) : host;
}
