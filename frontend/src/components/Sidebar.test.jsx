import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router-dom';
import Sidebar from './Sidebar';
import { loginUser } from '../features/authSlice';
import { makeStore } from '../redux/store';
import { apiSlice } from '../services/apiSlice';
import api from '../services/api';
import { controlsWithoutFocusIndicator } from '../test/focusIndicator';

vi.mock('../services/api');

const GB = 1024 ** 3;

const renderSidebar = ({ role = 'USER', isCollapsed = false } = {}) => {
    const store = makeStore();
    store.dispatch(loginUser.fulfilled({ username: 'ada', role }, 'seed'));
    const view = render(
        <Provider store={store}>
            <MemoryRouter>
                <Sidebar onClose={vi.fn()} isCollapsed={isCollapsed} toggleCollapse={vi.fn()} />
            </MemoryRouter>
        </Provider>,
    );
    return { ...view, store };
};

describe('Sidebar', () => {
    afterEach(cleanup);
    beforeEach(() => vi.clearAllMocks());

    it('shows where keyboard focus is, on the collapse toggle too', () => {
        api.get.mockResolvedValue({ data: { usedBytes: 0, quotaBytes: null } });
        const { container } = renderSidebar();

        expect(controlsWithoutFocusIndicator(container)).toEqual([]);
    });

    it('measures what is stored against the quota', async () => {
        api.get.mockResolvedValue({ data: { usedBytes: GB, quotaBytes: 4 * GB } });
        renderSidebar();

        const meter = await screen.findByRole('meter', { name: 'Storage used' });
        expect(meter).toHaveAttribute('aria-valuenow', String(GB));
        expect(meter).toHaveAttribute('aria-valuemax', String(4 * GB));
        expect(screen.getByText('1 GB of 4 GB used')).toBeInTheDocument();
        expect(api.get).toHaveBeenCalledWith('/api/storage', expect.anything());
    });

    it('without a quota, says what is stored and draws no meter', async () => {
        api.get.mockResolvedValue({ data: { usedBytes: 2048, quotaBytes: null } });
        renderSidebar();

        expect(await screen.findByText('2 KB stored')).toBeInTheDocument();
        expect(screen.queryByRole('meter')).toBeNull();
    });

    // Every change that stores, removes or restores a file, imports files or changes a quota
    // invalidates the Storage tag.
    it('asks again after a change to what is stored', async () => {
        api.get.mockResolvedValue({ data: { usedBytes: 0, quotaBytes: null } });
        const { store } = renderSidebar();
        await screen.findByText('0 B stored');

        api.get.mockResolvedValue({ data: { usedBytes: 4096, quotaBytes: null } });
        store.dispatch(apiSlice.util.invalidateTags(['Storage']));

        expect(await screen.findByText('4 KB stored')).toBeInTheDocument();
    });

    it('links admins, and only admins, to the accounts', async () => {
        api.get.mockResolvedValue({ data: { usedBytes: 0, quotaBytes: null } });
        renderSidebar({ role: 'ADMIN' });
        expect(screen.getByRole('link', { name: 'Users' })).toHaveAttribute('href', '/admin');
        cleanup();

        renderSidebar({ role: 'USER' });
        expect(screen.queryByRole('link', { name: 'Users' })).toBeNull();
        expect(screen.getByRole('link', { name: 'My Files' })).toBeInTheDocument();
    });
});
