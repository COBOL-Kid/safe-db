import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import ConnectionForm from '$lib/components/ConnectionForm.svelte';
import * as api from '$lib/api';

vi.mock('$lib/api');

describe('ConnectionForm', () => {
	afterEach(() => {
		cleanup();
	});

	it('passes empty password to test and save', async () => {
		const user = userEvent.setup();
		const onSaved = vi.fn();
		const onCancel = vi.fn();

		vi.mocked(api.testConnection).mockResolvedValue('PostgreSQL 16');
		vi.mocked(api.saveConnection).mockResolvedValue();

		render(ConnectionForm, { props: { onSaved, onCancel } });

		await user.click(screen.getByRole('button', { name: 'Test Connection' }));
		expect(api.testConnection).toHaveBeenCalledWith(
			expect.objectContaining({
				dialect: 'Postgres',
				host: 'localhost',
				port: 5432
			}),
			''
		);

		await user.click(screen.getByRole('button', { name: 'Save Connection' }));
		expect(api.saveConnection).toHaveBeenCalledWith(expect.any(Object), '');
		expect(onSaved).toHaveBeenCalled();
	});

	it('updates default port when dialect changes', async () => {
		const user = userEvent.setup();
		render(ConnectionForm, {
			props: { onSaved: vi.fn(), onCancel: vi.fn() }
		});

		await user.click(screen.getByRole('button', { name: 'MySQL' }));
		expect(screen.getByLabelText('Port')).toHaveValue(3306);
	});
});
