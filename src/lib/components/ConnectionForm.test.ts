import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import ConnectionForm from '$lib/components/ConnectionForm.svelte';
import * as api from '$lib/api';

vi.mock('$lib/api');

async function openGuidedCredentials(user: ReturnType<typeof userEvent.setup>, locationName: string) {
	render(ConnectionForm, { props: { onSaved: vi.fn(), onCancel: vi.fn() } });
	await user.click(screen.getByRole('button', { name: 'Help me set it up Local, cloud, or work database' }));
	await user.click(screen.getByRole('button', { name: locationName }));
}

describe('ConnectionForm', () => {
	afterEach(() => {
		cleanup();
		vi.mocked(api.testConnection).mockReset();
		vi.mocked(api.saveConnection).mockReset();
	});

	it('starts with entry path cards instead of transport controls', () => {
		render(ConnectionForm, { props: { onSaved: vi.fn(), onCancel: vi.fn() } });

		expect(screen.getByRole('button', { name: 'I have a connection string Paste from your host or dashboard' })).toBeInTheDocument();
		expect(screen.getByRole('button', { name: 'Help me set it up Local, cloud, or work database' })).toBeInTheDocument();
		expect(screen.queryByRole('group', { name: 'Transport security' })).not.toBeInTheDocument();
	});

	it('passes empty password to test and save after guided cloud setup', async () => {
		const user = userEvent.setup();
		const onSaved = vi.fn();
		const onCancel = vi.fn();

		vi.mocked(api.testConnection).mockResolvedValue('PostgreSQL 16');
		vi.mocked(api.saveConnection).mockResolvedValue();

		render(ConnectionForm, { props: { onSaved, onCancel } });
		await user.click(screen.getByRole('button', { name: 'Help me set it up Local, cloud, or work database' }));
		await user.click(screen.getByRole('button', { name: 'Online or in the cloud AWS, Google, Supabase, etc.' }));

		await user.type(screen.getByLabelText('Database'), 'app');
		await user.type(screen.getByLabelText('Username'), 'app');

		await user.click(screen.getByRole('button', { name: 'Test Connection' }));
		expect(api.testConnection).toHaveBeenCalledWith(
			expect.objectContaining({
				dialect: 'Postgres',
				host: 'localhost',
				port: 5432,
				transport_security: expect.objectContaining({ mode: 'VerifyIdentity' })
			}),
			''
		);

		await user.click(screen.getByRole('button', { name: 'Save Connection' }));
		expect(api.saveConnection).toHaveBeenCalledWith(expect.any(Object), '');
		expect(onSaved).toHaveBeenCalled();
	});

	it('requires insecure acknowledgement for guided local setup', async () => {
		const user = userEvent.setup();
		await openGuidedCredentials(user, 'On this computer Local development or testing');

		await user.type(screen.getByLabelText('Database'), 'app');
		await user.type(screen.getByLabelText('Username'), 'app');
		await user.click(screen.getByRole('button', { name: 'Test Connection' }));

		expect(await screen.findByText('Acknowledge the insecure transport setting before continuing')).toBeInTheDocument();
		expect(api.testConnection).not.toHaveBeenCalled();
	});

	it('switches guided local defaults to cloud defaults when host becomes remote', async () => {
		const user = userEvent.setup();
		vi.mocked(api.testConnection).mockResolvedValue('PostgreSQL 16');
		await openGuidedCredentials(user, 'On this computer Local development or testing');

		await user.clear(screen.getByLabelText('Host'));
		await user.type(screen.getByLabelText('Host'), 'db.example.com');
		await user.type(screen.getByLabelText('Database'), 'app');
		await user.type(screen.getByLabelText('Username'), 'app');
		await user.click(screen.getByRole('button', { name: 'Test Connection' }));

		await waitFor(() => {
			expect(api.testConnection).toHaveBeenCalledWith(
				expect.objectContaining({
					host: 'db.example.com',
					transport_security: expect.objectContaining({ mode: 'VerifyIdentity' })
				}),
				''
			);
		});
	});

	it('updates default port when dialect changes', async () => {
		const user = userEvent.setup();
		await openGuidedCredentials(user, 'Online or in the cloud AWS, Google, Supabase, etc.');

		await user.click(screen.getByRole('button', { name: 'MySQL' }));
		expect(screen.getByLabelText('Port')).toHaveValue(3306);
	});

	it('toggles password visibility while preserving empty password', async () => {
		const user = userEvent.setup();
		vi.mocked(api.testConnection).mockResolvedValue('PostgreSQL 16');
		vi.mocked(api.saveConnection).mockResolvedValue();

		await openGuidedCredentials(user, 'Online or in the cloud AWS, Google, Supabase, etc.');

		await user.type(screen.getByLabelText('Database'), 'app');
		await user.type(screen.getByLabelText('Username'), 'app');

		const passwordInput = screen.getByLabelText('Password') as HTMLInputElement;
		expect(passwordInput.type).toBe('password');

		await user.click(screen.getByRole('button', { name: 'Show password' }));
		expect(passwordInput.type).toBe('text');

		await user.click(screen.getByRole('button', { name: 'Hide password' }));
		expect(passwordInput.type).toBe('password');

		await user.click(screen.getByRole('button', { name: 'Test Connection' }));
		expect(api.testConnection).toHaveBeenLastCalledWith(expect.any(Object), '');
	});

	it('parses a connection string, shows summary chips, and submits parsed values', async () => {
		const user = userEvent.setup();
		vi.mocked(api.testConnection).mockResolvedValue('PostgreSQL 16');

		render(ConnectionForm, { props: { onSaved: vi.fn(), onCancel: vi.fn() } });
		await user.click(screen.getByRole('button', { name: 'I have a connection string Paste from your host or dashboard' }));
		await user.type(
			screen.getByLabelText('Connection string'),
			'postgresql://readonly:p%40ss@db.example.com:5432/app?sslmode=verify-full'
		);
		await user.click(screen.getByRole('button', { name: 'Continue' }));

		expect(screen.getByText('db.example.com:5432')).toBeInTheDocument();
		expect(screen.getAllByText('Secure connection').length).toBeGreaterThan(0);
		expect(screen.getByLabelText('Database')).toHaveValue('app');
		expect(screen.getByLabelText('Username')).toHaveValue('readonly');

		await user.click(screen.getByRole('button', { name: 'Test Connection' }));
		await waitFor(() => {
			expect(api.testConnection).toHaveBeenCalledWith(
				expect.objectContaining({
					host: 'db.example.com',
					database: 'app',
					username: 'readonly',
					transport_security: expect.objectContaining({ mode: 'VerifyIdentity' })
				}),
				'p@ss'
			);
		});
	});

	it('clears a previous parsed password when the next parsed string has none', async () => {
		const user = userEvent.setup();
		vi.mocked(api.testConnection).mockResolvedValue('PostgreSQL 16');

		render(ConnectionForm, { props: { onSaved: vi.fn(), onCancel: vi.fn() } });
		await user.click(screen.getByRole('button', { name: 'I have a connection string Paste from your host or dashboard' }));
		await user.type(
			screen.getByLabelText('Connection string'),
			'postgresql://readonly:secret@db.example.com:5432/app?sslmode=verify-full'
		);
		await user.click(screen.getByRole('button', { name: 'Continue' }));

		await user.click(screen.getByRole('button', { name: 'Change path' }));
		await user.click(screen.getByRole('button', { name: 'I have a connection string Paste from your host or dashboard' }));
		await user.clear(screen.getByLabelText('Connection string'));
		await user.type(
			screen.getByLabelText('Connection string'),
			'postgresql://readonly@other.example.com:5432/app?sslmode=verify-full'
		);
		await user.click(screen.getByRole('button', { name: 'Continue' }));
		await user.click(screen.getByRole('button', { name: 'Test Connection' }));

		await waitFor(() => {
			expect(api.testConnection).toHaveBeenLastCalledWith(
				expect.objectContaining({ host: 'other.example.com' }),
				''
			);
		});
	});

	it('requires an Oracle wallet before testing verified Oracle connections', async () => {
		const user = userEvent.setup();
		await openGuidedCredentials(user, 'Online or in the cloud AWS, Google, Supabase, etc.');

		await user.click(screen.getByRole('button', { name: 'Oracle' }));
		await user.type(screen.getByLabelText('Database'), 'svc');
		await user.type(screen.getByLabelText('Username'), 'readonly');
		await user.click(screen.getByRole('button', { name: 'Test Connection' }));

		expect(await screen.findByText('Oracle TCPS requires a wallet location')).toBeInTheDocument();
		expect(api.testConnection).not.toHaveBeenCalled();
	});

	it('lets certificate troubleshooting paste PEM and retry with VerifyCa', async () => {
		const user = userEvent.setup();
		vi.mocked(api.testConnection)
			.mockRejectedValueOnce('certificate verify failed: unknown issuer')
			.mockResolvedValueOnce('PostgreSQL 16');

		render(ConnectionForm, { props: { onSaved: vi.fn(), onCancel: vi.fn() } });
		await user.click(screen.getByRole('button', { name: 'Help me set it up Local, cloud, or work database' }));
		await user.click(screen.getByRole('button', { name: 'From my organization Work or school database' }));
		await user.clear(screen.getByLabelText('Host'));
		await user.type(screen.getByLabelText('Host'), 'db.example.com');
		await user.type(screen.getByLabelText('Database'), 'app');
		await user.type(screen.getByLabelText('Username'), 'readonly');
		await user.click(screen.getByRole('button', { name: 'Test Connection' }));

		expect(await screen.findByText('Your organization may require a security file.')).toBeInTheDocument();

		await user.type(screen.getByLabelText('CA certificate PEM'), '-----BEGIN CERTIFICATE-----\\nabc\\n-----END CERTIFICATE-----');
		await user.click(screen.getByRole('button', { name: 'Test Connection' }));

		await waitFor(() => {
			expect(api.testConnection).toHaveBeenLastCalledWith(
				expect.objectContaining({
					transport_security: expect.objectContaining({
						mode: 'VerifyCa',
						ca_pem: '-----BEGIN CERTIFICATE-----\\nabc\\n-----END CERTIFICATE-----'
					})
				}),
				''
			);
		});
	});

	it('exposes the legacy transport controls in Advanced connection settings', async () => {
		const user = userEvent.setup();
		await openGuidedCredentials(user, 'Online or in the cloud AWS, Google, Supabase, etc.');

		await user.click(screen.getByText('Advanced connection settings'));
		const transportGroup = screen.getByRole('group', { name: 'Transport security' });
		expect(within(transportGroup).getByRole('button', { name: 'Verify identity' })).toBeInTheDocument();
		expect(within(transportGroup).getByRole('button', { name: 'Verify CA' })).toBeInTheDocument();
		expect(within(transportGroup).getByRole('button', { name: 'Encrypt only' })).toBeInTheDocument();
		expect(within(transportGroup).getByRole('button', { name: 'Disabled' })).toBeInTheDocument();
	});
});
