import type { TransportSecurity, TransportSecurityMode } from '$lib/ir';

export type DatabaseLocation = 'local' | 'cloud' | 'organization';

export type SecurityLabel = {
	tone: 'success' | 'warning' | 'danger';
	text: string;
};

export function transportPresetForLocation(location: DatabaseLocation): TransportSecurity {
	if (location === 'local') {
		return {
			mode: 'Disabled',
			ca_pem: null,
			oracle_wallet_location: null,
			insecure_acknowledged: false,
			legacy_implicit: false
		};
	}

	return {
		mode: 'VerifyIdentity',
		ca_pem: null,
		oracle_wallet_location: null,
		insecure_acknowledged: false,
		legacy_implicit: false
	};
}

export function securityLabelForMode(mode: TransportSecurityMode): SecurityLabel {
	switch (mode) {
		case 'VerifyIdentity':
		case 'VerifyCa':
			return { tone: 'success', text: 'Secure connection' };
		case 'EncryptOnly':
			return { tone: 'warning', text: 'Encrypted (certificate not verified)' };
		case 'Disabled':
			return { tone: 'danger', text: 'Not encrypted - local only' };
	}
}

export function isLocalHost(host: string): boolean {
	const normalized = host.trim().toLowerCase();
	return (
		normalized === 'localhost' ||
		normalized === '127.0.0.1' ||
		normalized === '::1' ||
		normalized === '[::1]'
	);
}

export function inferLocation(host: string): Exclude<DatabaseLocation, 'organization'> {
	return isLocalHost(host) ? 'local' : 'cloud';
}
