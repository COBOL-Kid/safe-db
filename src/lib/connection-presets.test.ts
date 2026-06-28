import { describe, expect, it } from 'vitest';
import {
	inferLocation,
	isLocalHost,
	securityLabelForMode,
	transportPresetForLocation
} from '$lib/connection-presets';

describe('connection presets', () => {
	it('maps local databases to disabled transport', () => {
		expect(transportPresetForLocation('local')).toMatchObject({
			mode: 'Disabled',
			legacy_implicit: false
		});
	});

	it('maps cloud and organization databases to verified identity transport', () => {
		expect(transportPresetForLocation('cloud').mode).toBe('VerifyIdentity');
		expect(transportPresetForLocation('organization').mode).toBe('VerifyIdentity');
	});

	it('labels transport security modes consistently', () => {
		expect(securityLabelForMode('VerifyIdentity')).toEqual({
			tone: 'success',
			text: 'Secure connection'
		});
		expect(securityLabelForMode('VerifyCa')).toEqual({
			tone: 'success',
			text: 'Secure connection'
		});
		expect(securityLabelForMode('EncryptOnly')).toEqual({
			tone: 'warning',
			text: 'Encrypted (certificate not verified)'
		});
		expect(securityLabelForMode('Disabled')).toEqual({
			tone: 'danger',
			text: 'Not encrypted'
		});
		expect(securityLabelForMode('Disabled', 'localhost')).toEqual({
			tone: 'danger',
			text: 'Not encrypted - local only'
		});
		expect(securityLabelForMode('Disabled', 'db.example.com')).toEqual({
			tone: 'danger',
			text: 'Not encrypted'
		});
	});

	it('infers only loopback hosts as local', () => {
		expect(isLocalHost('localhost')).toBe(true);
		expect(isLocalHost('127.0.0.1')).toBe(true);
		expect(isLocalHost('[::1]')).toBe(true);
		expect(inferLocation('db.example.com')).toBe('cloud');
	});
});
