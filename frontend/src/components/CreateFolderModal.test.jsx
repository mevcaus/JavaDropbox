import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import CreateFolderModal from './CreateFolderModal';

afterEach(cleanup);

const renderModal = (props = {}) => {
    const handlers = { onClose: vi.fn(), onCreate: vi.fn(), ...props };
    const view = render(<CreateFolderModal isOpen {...handlers} />);
    return { ...view, ...handlers };
};

const nameInput = () => screen.getByPlaceholderText('Folder Name');

describe('CreateFolderModal', () => {
    it('renders nothing while closed', () => {
        render(<CreateFolderModal isOpen={false} onClose={vi.fn()} onCreate={vi.fn()} />);
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('submits the trimmed folder name and closes', async () => {
        const user = userEvent.setup();
        const { onCreate, onClose } = renderModal();

        await user.type(nameInput(), '  Invoices  ');
        await user.click(screen.getByRole('button', { name: 'Create' }));

        expect(onCreate).toHaveBeenCalledWith('Invoices');
        expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('ignores a whitespace-only name instead of creating an unnamed folder', async () => {
        const user = userEvent.setup();
        const { onCreate, onClose } = renderModal();

        // Non-empty, so it passes the input's `required` check and reaches the trim guard
        await user.type(nameInput(), '   ');
        await user.click(screen.getByRole('button', { name: 'Create' }));

        expect(onCreate).not.toHaveBeenCalled();
        expect(onClose).not.toHaveBeenCalled();
    });

    // The reset lives in handleClose rather than in an open-effect, so every close path
    // has to clear the field or a discarded name would reappear on the next open.
    it.each([
        ['Cancel', () => screen.getByRole('button', { name: 'Cancel' })],
        ['Close', () => screen.getByRole('button', { name: 'Close' })],
    ])('clears a discarded name when closed via %s', async (_label, getButton) => {
        const user = userEvent.setup();
        const { rerender, onClose } = renderModal();

        await user.type(nameInput(), 'scratch');
        await user.click(getButton());
        expect(onClose).toHaveBeenCalledTimes(1);

        // Dashboard flips isOpen back on for the next "New Folder" click
        rerender(<CreateFolderModal isOpen={false} onClose={onClose} onCreate={vi.fn()} />);
        rerender(<CreateFolderModal isOpen onClose={onClose} onCreate={vi.fn()} />);

        expect(nameInput()).toHaveValue('');
    });

    it('clears the name after a successful create', async () => {
        const user = userEvent.setup();
        const { rerender, onCreate, onClose } = renderModal();

        await user.type(nameInput(), 'Reports');
        await user.click(screen.getByRole('button', { name: 'Create' }));
        expect(onCreate).toHaveBeenCalledWith('Reports');

        rerender(<CreateFolderModal isOpen={false} onClose={onClose} onCreate={onCreate} />);
        rerender(<CreateFolderModal isOpen onClose={onClose} onCreate={onCreate} />);

        expect(nameInput()).toHaveValue('');
    });
});
