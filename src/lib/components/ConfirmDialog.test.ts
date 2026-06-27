import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import ConfirmDialog from '$lib/components/ConfirmDialog.svelte';

describe('ConfirmDialog', () => {
	afterEach(() => {
		cleanup();
	});

	it('renders title and message when open', () => {
		render(ConfirmDialog, {
			props: {
				open: true,
				title: 'Delete connection?',
				message: 'Delete connection "Test"? This cannot be undone.',
				onConfirm: vi.fn(),
				onCancel: vi.fn()
			}
		});

		expect(screen.getByRole('alertdialog')).toBeInTheDocument();
		expect(screen.getByText('Delete connection?')).toBeInTheDocument();
		expect(screen.getByText(/Delete connection "Test"/)).toBeInTheDocument();
	});

	it('does not render when closed', () => {
		render(ConfirmDialog, {
			props: {
				open: false,
				title: 'Delete connection?',
				message: 'Are you sure?',
				onConfirm: vi.fn(),
				onCancel: vi.fn()
			}
		});

		expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
	});

	it('calls onConfirm when confirm is clicked', async () => {
		const user = userEvent.setup();
		const onConfirm = vi.fn();
		const onCancel = vi.fn();

		render(ConfirmDialog, {
			props: {
				open: true,
				title: 'Delete?',
				message: 'Confirm delete.',
				onConfirm,
				onCancel
			}
		});

		await user.click(screen.getByRole('button', { name: 'Delete' }));
		expect(onConfirm).toHaveBeenCalledOnce();
		expect(onCancel).not.toHaveBeenCalled();
	});

	it('calls onCancel when cancel is clicked', async () => {
		const user = userEvent.setup();
		const onConfirm = vi.fn();
		const onCancel = vi.fn();

		render(ConfirmDialog, {
			props: {
				open: true,
				title: 'Delete?',
				message: 'Confirm delete.',
				onConfirm,
				onCancel
			}
		});

		await user.click(screen.getByRole('button', { name: 'Cancel' }));
		expect(onCancel).toHaveBeenCalledOnce();
		expect(onConfirm).not.toHaveBeenCalled();
	});

	it('calls onCancel when Escape is pressed', async () => {
		const onConfirm = vi.fn();
		const onCancel = vi.fn();

		render(ConfirmDialog, {
			props: {
				open: true,
				title: 'Delete?',
				message: 'Confirm delete.',
				onConfirm,
				onCancel
			}
		});

		await fireEvent.keyDown(window, { key: 'Escape' });
		expect(onCancel).toHaveBeenCalledOnce();
		expect(onConfirm).not.toHaveBeenCalled();
	});

	it('calls onCancel when backdrop is clicked', async () => {
		const user = userEvent.setup();
		const onConfirm = vi.fn();
		const onCancel = vi.fn();

		render(ConfirmDialog, {
			props: {
				open: true,
				title: 'Delete?',
				message: 'Confirm delete.',
				onConfirm,
				onCancel
			}
		});

		const backdrop = document.querySelector('[role="presentation"]');
		expect(backdrop).not.toBeNull();
		await user.click(backdrop!);
		expect(onCancel).toHaveBeenCalledOnce();
		expect(onConfirm).not.toHaveBeenCalled();
	});
});
