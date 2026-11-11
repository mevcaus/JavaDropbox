import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render as renderWithoutStore, screen } from '@testing-library/react';
import { Provider } from 'react-redux';
import { makeStore } from '../redux/store';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import VersionHistoryModal from './VersionHistoryModal';
import api from '../services/api';

// The dialogs get their data through the API slice, so they need a store. rerender keeps it, as the
// app keeps one store while dialogs open and close.
const render = (ui) => {
    const store = makeStore();
    return renderWithoutStore(ui, { wrapper: ({ children }) => <Provider store={store}>{children}</Provider> });
};

vi.mock('../services/api');

const addToast = vi.fn();
vi.mock('../hooks/useToast', () => ({
    useToast: () => ({ addToast }),
}));

const FILE = { id: 7, name: 'report.txt' };
const VERSIONS = [
    { id: 2, version: 2, size: 2048, createdAt: '2026-03-01T10:00:00Z', createdBy: 'ada' },
    { id: 1, version: 1, size: 12, createdAt: '2026-02-01T10:00:00Z', createdBy: 'ada' },
];

const renderModal = (props = {}) => {
    const handlers = { onClose: vi.fn(), ...props };
    render(<VersionHistoryModal isOpen file={FILE} {...handlers} />);
    return handlers;
};

describe('VersionHistoryModal', () => {
    afterEach(() => {
        cleanup();
        vi.clearAllMocks();
    });

    it('lists the versions with their size', async () => {
        api.get.mockResolvedValueOnce({ data: VERSIONS });
        renderModal();

        expect(await screen.findByText('Version 2')).toBeInTheDocument();
        expect(screen.getByText(/2 KB/)).toBeInTheDocument();
        expect(api.get).toHaveBeenCalledWith('/api/files/7/versions', expect.anything());
    });

    it('explains an empty history', async () => {
        api.get.mockResolvedValueOnce({ data: [] });
        renderModal();

        expect(await screen.findByText(/No previous versions yet/)).toBeInTheDocument();
    });

    it.each([
        ['Restore', 'OVERWRITE'],
        ['Restore as copy', 'COPY'],
    ])('%s restores that version in %s mode, says so and closes', async (label, mode) => {
        api.get.mockResolvedValueOnce({ data: VERSIONS });
        api.post.mockResolvedValueOnce({ data: { message: 'Version 1 restored' } });
        const user = userEvent.setup();
        const { onClose } = renderModal();

        await screen.findByText('Version 1');
        const buttons = screen.getAllByRole('button', { name: label });
        await user.click(buttons[1]);

        expect(api.post).toHaveBeenCalledWith(
            '/api/files/7/versions/1/restore',
            undefined,
            expect.objectContaining({ params: { mode } }),
        );
        expect(addToast).toHaveBeenCalledWith(
            mode === 'COPY' ? 'Version 1 restored as a copy.' : 'Version 1 restored.',
            'success',
        );
        await vi.waitFor(() => expect(onClose).toHaveBeenCalledOnce());
    });

    // A restore's outcome is a toast, like an upload's or a delete's, since the dialog may have given
    // way to another file's by the time it finishes.
    it('keeps the dialog open and usable, and reports the reason, when a restore fails', async () => {
        api.get.mockResolvedValueOnce({ data: VERSIONS });
        api.post.mockRejectedValueOnce({ response: { status: 404, data: { message: 'The stored copy of version 1 is missing' } } });
        const user = userEvent.setup();
        const { onClose } = renderModal();

        await screen.findByText('Version 1');
        await user.click(screen.getAllByRole('button', { name: 'Restore' })[1]);

        await vi.waitFor(() => expect(addToast).toHaveBeenCalledWith('The stored copy of version 1 is missing', 'error'));
        expect(onClose).not.toHaveBeenCalled();
        expect(screen.getAllByRole('button', { name: 'Restore' })[1]).toBeEnabled();
    });

    // The dialog stays mounted from one file to the next, as Dashboard renders it.
    describe('when a restore for another file is still running', () => {
        const A = { id: 1, name: 'a.txt' };
        const B = { id: 2, name: 'b.txt' };
        const ONE_VERSION = [{ id: 10, version: 1, size: 1, createdAt: '2026-02-01T10:00:00Z', createdBy: 'ada' }];

        const Harness = () => {
            const [file, setFile] = useState(null);
            return (
                <>
                    <button onClick={() => setFile(A)}>open a</button>
                    <button onClick={() => setFile(B)}>open b</button>
                    <VersionHistoryModal isOpen={file !== null} file={file} onClose={() => setFile(null)} />
                </>
            );
        };

        // Starts a restore for a.txt, leaves it running, and opens b.txt's history.
        const startRestoreForAThenOpenB = async () => {
            api.get.mockResolvedValue({ data: ONE_VERSION });
            const restoreA = {};
            api.post.mockReturnValueOnce(new Promise((resolve, reject) => Object.assign(restoreA, { resolve, reject })));
            const user = userEvent.setup();
            render(<Harness />);

            await user.click(screen.getByText('open a'));
            await user.click(await screen.findByRole('button', { name: 'Restore' }));
            await user.click(screen.getByRole('button', { name: 'Done' }));
            await user.click(screen.getByText('open b'));
            await screen.findByRole('dialog', { name: /b\.txt/ });
            await screen.findByText('Version 1');
            return restoreA;
        };

        it('starts b.txt\'s dialog with its buttons idle', async () => {
            await startRestoreForAThenOpenB();

            const buttons = screen.getAllByRole('button', { name: /Restor/ });
            expect(buttons.map((button) => button.textContent)).toEqual(['Restore', 'Restore as copy']);
            buttons.forEach((button) => expect(button).toBeEnabled());
        });

        it('does not close b.txt\'s dialog when a.txt\'s restore finishes', async () => {
            const restoreA = await startRestoreForAThenOpenB();

            await act(async () => restoreA.resolve({ data: { message: 'Version 1 restored' } }));

            expect(screen.getByRole('dialog', { name: /b\.txt/ })).toBeInTheDocument();
            // The restore did happen, so the user still hears of it (and the tree refreshes through its
            // cache tag; see apiSlice.test.js).
            expect(addToast).toHaveBeenCalledWith('Version 1 restored.', 'success');
        });

        it('reports a.txt\'s failure as a toast, not in b.txt\'s dialog', async () => {
            const restoreA = await startRestoreForAThenOpenB();

            await act(async () => restoreA.reject({ response: { status: 404, data: { message: 'a.txt is gone' } } }));

            expect(screen.queryByText('a.txt is gone')).not.toBeInTheDocument();
            expect(addToast).toHaveBeenCalledWith('a.txt is gone', 'error');
        });
    });
});
