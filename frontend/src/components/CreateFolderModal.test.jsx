import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
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

    it('explains that a name cannot start with a dot instead of sending it', async () => {
        const user = userEvent.setup();
        const { onCreate, onClose } = renderModal();

        await user.type(nameInput(), '.config');
        await user.click(screen.getByRole('button', { name: 'Create' }));

        expect(screen.getByRole('alert')).toHaveTextContent('Names cannot start with a dot.');
        expect(onCreate).not.toHaveBeenCalled();
        expect(onClose).not.toHaveBeenCalled();
    });

    describe('while the server decides', () => {
        const deferred = () => {
            let resolve;
            let reject;
            const promise = new Promise((res, rej) => {
                resolve = res;
                reject = rej;
            });
            return { promise, resolve, reject };
        };

        it('stays open, cannot be submitted twice, and closes once the folder exists', async () => {
            const user = userEvent.setup();
            const request = deferred();
            const { onCreate, onClose } = renderModal({ onCreate: vi.fn(() => request.promise) });

            await user.type(nameInput(), 'Reports');
            await user.click(screen.getByRole('button', { name: 'Create' }));

            expect(onClose).not.toHaveBeenCalled();
            expect(screen.getByRole('button', { name: 'Creating' })).toBeDisabled();
            await user.keyboard('{Enter}');
            expect(onCreate).toHaveBeenCalledTimes(1);

            await act(async () => request.resolve());
            expect(onClose).toHaveBeenCalledTimes(1);
        });

        it('cannot be dismissed until the server answers', async () => {
            const user = userEvent.setup();
            const { onClose } = renderModal({ onCreate: vi.fn(() => new Promise(() => {})) });

            await user.type(nameInput(), 'Reports');
            await user.click(screen.getByRole('button', { name: 'Create' }));
            await user.click(screen.getByRole('button', { name: 'Cancel' }));
            await user.keyboard('{Escape}');

            expect(onClose).not.toHaveBeenCalled();
        });

        it('keeps the dialog, the name and the reason when the server refuses', async () => {
            const user = userEvent.setup();
            const { onClose } = renderModal({ onCreate: vi.fn().mockRejectedValue('Reports already exists') });

            await user.type(nameInput(), 'Reports');
            await user.click(screen.getByRole('button', { name: 'Create' }));

            expect(await screen.findByRole('alert')).toHaveTextContent('Reports already exists');
            expect(nameInput()).toHaveValue('Reports');
            expect(nameInput()).toHaveFocus();
            expect(screen.getByRole('button', { name: 'Create' })).toBeEnabled();
            expect(onClose).not.toHaveBeenCalled();
        });

        it('forgets the reason once the dialog is closed', async () => {
            const user = userEvent.setup();
            const { rerender, onClose } = renderModal({ onCreate: vi.fn().mockRejectedValue('Reports already exists') });

            await user.type(nameInput(), 'Reports');
            await user.click(screen.getByRole('button', { name: 'Create' }));
            await screen.findByRole('alert');
            await user.click(screen.getByRole('button', { name: 'Cancel' }));
            rerender(<CreateFolderModal isOpen={false} onClose={onClose} onCreate={vi.fn()} />);
            rerender(<CreateFolderModal isOpen onClose={onClose} onCreate={vi.fn()} />);

            expect(screen.queryByRole('alert')).not.toBeInTheDocument();
        });
    });
});
