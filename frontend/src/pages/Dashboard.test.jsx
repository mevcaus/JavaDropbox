import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { configureStore } from '@reduxjs/toolkit';
import { Provider } from 'react-redux';
import Dashboard from './Dashboard';
import filesReducer from '../features/filesSlice';
import authReducer from '../features/authSlice';
import api from '../services/api';

vi.mock('../services/api');

const addToast = vi.fn();
vi.mock('../hooks/useToast', () => ({
    useToast: () => ({ addToast }),
}));

const TREE = [{ name: 'report.pdf', isDirectory: false, size: 10, lastModified: '2026-01-01T00:00:00Z', relativePath: 'report.pdf' }];

const renderDashboard = () => {
    const store = configureStore({ reducer: { auth: authReducer, files: filesReducer } });
    render(
        <Provider store={store}>
            <Dashboard />
        </Provider>,
    );
    return store;
};

describe('Dashboard', () => {
    afterEach(() => {
        cleanup();
        // reset, not clear: a queued mockResolvedValueOnce must not leak into the next test.
        vi.resetAllMocks();
    });

    it('downloads through a plain link instead of loading the file into memory', async () => {
        api.get.mockResolvedValue({ data: TREE });
        const user = userEvent.setup();
        const clicked = [];
        const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function record() {
            clicked.push(this.getAttribute('href'));
        });
        renderDashboard();

        await user.click(await screen.findByRole('button', { name: 'Download' }));

        expect(clicked).toEqual(['/api/files/download?path=report.pdf']);
        // Only the tree was fetched; the file itself never went through XHR.
        expect(api.get).toHaveBeenCalledTimes(1);
        click.mockRestore();
    });

    it('clears the file input so the same file can be uploaded again', async () => {
        api.get.mockResolvedValue({ data: TREE });
        api.post.mockResolvedValue({ data: {} });
        const user = userEvent.setup();
        renderDashboard();
        await screen.findByRole('button', { name: 'Download' });
        const input = document.querySelector('input[type="file"]');

        await user.upload(input, new File(['x'], 'again.txt'));

        await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
        expect(input.value).toBe('');
    });

    it('keeps the table on screen while the list refreshes', async () => {
        api.get.mockResolvedValueOnce({ data: TREE });
        const store = renderDashboard();
        await screen.findByRole('button', { name: 'Download' });

        store.dispatch({ type: 'files/fetchFiles/pending' });

        expect(screen.getByRole('button', { name: 'Download' })).toBeInTheDocument();
        expect(screen.queryByLabelText('Loading files')).not.toBeInTheDocument();
    });

    it('does not offer upload sources or installers that do not exist', async () => {
        api.get.mockResolvedValue({ data: TREE });
        renderDashboard();
        await screen.findByRole('button', { name: 'Download' });

        expect(screen.queryByText(/Google Drive|OneDrive|Install App/)).not.toBeInTheDocument();
    });
});
