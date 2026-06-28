import { describe, expect, it } from 'vitest';
import { classifyConnectionError } from '$lib/ssl-errors';

describe('classifyConnectionError', () => {
	it('classifies certificate trust failures as troubleshootable', () => {
		expect(
			classifyConnectionError('certificate verify failed: unknown issuer', {
				location: 'cloud',
				remoteHost: true
			})
		).toEqual({ kind: 'untrusted_ca', showTroubleshooting: true });
	});

	it('classifies hostname mismatch failures as troubleshootable', () => {
		expect(
			classifyConnectionError('certificate is not valid for db.internal', {
				location: 'cloud',
				remoteHost: true
			})
		).toEqual({ kind: 'hostname_mismatch', showTroubleshooting: true });
	});

	it('does not classify unrelated not-valid-for messages as hostname mismatch', () => {
		expect(
			classifyConnectionError("Login failed. The server principal 'app' is not valid for login.", {
				location: 'cloud',
				remoteHost: true
			})
		).toEqual({ kind: 'unknown', showTroubleshooting: false });
	});

	it('classifies certificate required errors as certificate-required', () => {
		expect(
			classifyConnectionError('certificate required', {
				location: 'cloud',
				remoteHost: true
			})
		).toEqual({ kind: 'certificate_required', showTroubleshooting: true });
	});

	it('classifies Oracle wallet and TLS-required errors as certificate-required', () => {
		expect(
			classifyConnectionError('Oracle TCPS requires a wallet location', {
				location: 'organization',
				remoteHost: true
			})
		).toEqual({ kind: 'certificate_required', showTroubleshooting: true });
	});

	it('shows organization troubleshooting only for unknown remote errors', () => {
		expect(
			classifyConnectionError('login failed', { location: 'organization', remoteHost: true })
		).toEqual({ kind: 'unknown', showTroubleshooting: true });
		expect(classifyConnectionError('login failed', { location: 'organization', remoteHost: false }))
			.toEqual({ kind: 'unknown', showTroubleshooting: false });
		expect(classifyConnectionError('login failed', { location: 'cloud', remoteHost: true })).toEqual({
			kind: 'unknown',
			showTroubleshooting: false
		});
	});
});
