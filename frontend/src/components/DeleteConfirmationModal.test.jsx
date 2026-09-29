import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import DeleteConfirmationModal from './DeleteConfirmationModal';

afterEach(cleanup);

describe('DeleteConfirmationModal', () => {
    it('confirms the delete', async () => {
        const user = userEvent.setup();
        const onConfirm = vi.fn();
        render(<DeleteConfirmationModal isOpen itemName="a.txt" onClose={vi.fn()} onConfirm={onConfirm} />);

        await user.click(screen.getByRole('button', { name: 'Delete' }));

        expect(onConfirm).toHaveBeenCalledOnce();
    });

    it('cannot be confirmed or dismissed again while the delete is in flight', async () => {
        const user = userEvent.setup();
        const onConfirm = vi.fn();
        const onClose = vi.fn();
        render(<DeleteConfirmationModal isOpen isDeleting itemName="a.txt" onClose={onClose} onConfirm={onConfirm} />);

        await user.click(screen.getByRole('button', { name: 'Deleting' }));
        await user.click(screen.getByRole('button', { name: 'Cancel' }));
        await user.keyboard('{Escape}');

        expect(onConfirm).not.toHaveBeenCalled();
        expect(onClose).not.toHaveBeenCalled();
    });
});
