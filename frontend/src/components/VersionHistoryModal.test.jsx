import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import VersionHistoryModal from './VersionHistoryModal';
import api from '../services/api';

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
    const handlers = { onClose: vi.fn(), onRestored: vi.fn(), ...props };
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
        expect(api.get).toHaveBeenCalledWith('/api/files/7/versions');
    });

    it('explains an empty history', async () => {
        api.get.mockResolvedValueOnce({ data: [] });
        renderModal();

        expect(await screen.findByText(/No previous versions yet/)).toBeInTheDocument();
    });

    it.each([
        ['Restore', 'OVERWRITE'],
        ['Restore as copy', 'COPY'],
    ])('%s restores that version in %s mode and refreshes', async (label, mode) => {
        api.get.mockResolvedValueOnce({ data: VERSIONS });
        api.post.mockResolvedValueOnce({ data: {} });
        const user = userEvent.setup();
        const { onClose, onRestored } = renderModal();

        await screen.findByText('Version 1');
        const buttons = screen.getAllByRole('button', { name: label });
        await user.click(buttons[1]);

        expect(api.post).toHaveBeenCalledWith('/api/files/7/versions/1/restore', null, { params: { mode } });
        expect(onRestored).toHaveBeenCalledOnce();
        expect(onClose).toHaveBeenCalledOnce();
    });

    it('keeps the dialog open with the reason when a restore fails', async () => {
        api.get.mockResolvedValueOnce({ data: VERSIONS });
        api.post.mockRejectedValueOnce({ response: { status: 404, data: { message: 'The stored copy of version 1 is missing' } } });
        const user = userEvent.setup();
        const { onClose } = renderModal();

        await screen.findByText('Version 1');
        await user.click(screen.getAllByRole('button', { name: 'Restore' })[1]);

        expect(await screen.findByText('The stored copy of version 1 is missing')).toBeInTheDocument();
        expect(onClose).not.toHaveBeenCalled();
    });
});
