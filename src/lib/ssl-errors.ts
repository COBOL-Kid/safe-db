import type { DatabaseLocation } from '$lib/connection-presets';

export type ConnectionErrorKind =
	| 'untrusted_ca'
	| 'hostname_mismatch'
	| 'certificate_required'
	| 'unknown';

export type ConnectionErrorClassification = {
	kind: ConnectionErrorKind;
	showTroubleshooting: boolean;
};

export function classifyConnectionError(
	message: string,
	context: { location: DatabaseLocation | null; remoteHost: boolean }
): ConnectionErrorClassification {
	const normalized = message.toLowerCase();

	if (
		normalized.includes('certificate verify failed') ||
		normalized.includes('unknown issuer') ||
		normalized.includes('unknown ca') ||
		normalized.includes('self signed') ||
		normalized.includes('self-signed') ||
		normalized.includes('invalid certificate') ||
		normalized.includes('certificate required')
	) {
		return { kind: 'untrusted_ca', showTroubleshooting: true };
	}

	if (
		normalized.includes('hostname mismatch') ||
		normalized.includes('name mismatch') ||
		normalized.includes('not valid for') ||
		normalized.includes('certificate is not valid for')
	) {
		return { kind: 'hostname_mismatch', showTroubleshooting: true };
	}

	if (
		normalized.includes('wallet') ||
		normalized.includes('tcps') ||
		normalized.includes('ssl required') ||
		normalized.includes('requires ssl') ||
		normalized.includes('requires tls')
	) {
		return { kind: 'certificate_required', showTroubleshooting: true };
	}

	return {
		kind: 'unknown',
		showTroubleshooting: context.location === 'organization' && context.remoteHost
	};
}
