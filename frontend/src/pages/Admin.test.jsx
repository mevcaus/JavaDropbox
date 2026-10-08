import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { configureStore } from '@reduxjs/toolkit';
import { Provider } from 'react-redux';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import Admin from './Admin';
import authReducer, { loginUser } from '../features/authSlice';
import api from '../services/api';
import { controlsWithoutFocusIndicator } from '../test/focusIndicator';

vi.mock('../services/api');

const addToast = vi.fn();
vi.mock('../hooks/useToast', () => ({
    useToast: () => ({ addToast }),
}));

const GB = 1024 ** 3;
const MB = 1024 ** 2;

const ACCOUNTS = [
    { id: 1, username: 'ada', role: 'ADMIN', enabled: true, quotaBytes: null, usedBytes: 3 * MB },
    { id: 2, username: 'bob', role: 'USER', enabled: true, quotaBytes: 5 * GB, usedBytes: GB },
    { id: 3, username: 'eve', role: 'USER', enabled: false, quotaBytes: null, usedBytes: 0 },
];
const INVITE = {
    id: 9,
    username: 'carol',
    role: 'USER',
    quotaBytes: 2 * GB,
    createdAt: '2026-10-01T09:00:00Z',
    expiresAt: '2026-10-08T09:00:00Z',
    createdBy: 'ada',
};

// Answers the page's two lists; each call to load them again gets the same answers.
const serve = ({ accounts = ACCOUNTS, invites = [INVITE] } = {}) =>
    api.get.mockImplementation(async (url) => {
        if (url === '/api/admin/users') return { data: accounts };
        if (url === '/api/admin/invites') return { data: invites };
        throw new Error(`unexpected GET ${url}`);
    });

const renderAs = (role, username = 'ada') => {
    const store = configureStore({ reducer: { auth: authReducer } });
    store.dispatch(loginUser.fulfilled({ username, role }, 'seed'));
    return render(
        <Provider store={store}>
            <MemoryRouter initialEntries={['/admin']}>
                <Routes>
                    <Route path="/admin" element={<Admin />} />
                    <Route path="/dashboard" element={<p>My Files</p>} />
                </Routes>
            </MemoryRouter>
        </Provider>,
    );
};

const rowOf = async (username) => (await screen.findByRole('cell', { name: new RegExp(`^${username}`) })).closest('tr');
const params = (call) => Object.fromEntries(call[1]);

describe('Admin', () => {
    afterEach(cleanup);
    beforeEach(() => {
        vi.clearAllMocks();
        localStorage.clear();
    });

    it('sends someone who is no admin to their files', () => {
        renderAs('USER');

        expect(screen.getByText('My Files')).toBeInTheDocument();
        expect(api.get).not.toHaveBeenCalled();
    });

    it('lists every account with its role, status and storage against its quota', async () => {
        serve();
        renderAs('ADMIN');

        const bob = await rowOf('bob');
        expect(within(bob).getByText('User')).toBeInTheDocument();
        expect(within(bob).getByText('Active')).toBeInTheDocument();
        expect(within(bob).getByText('1 GB of 5 GB')).toBeInTheDocument();
        expect(within(await rowOf('eve')).getByText('Disabled')).toBeInTheDocument();
        expect(within(await rowOf('ada')).getByText('3 MB (no limit)')).toBeInTheDocument();
    });

    it('offers no way to change your own role or disable yourself', async () => {
        serve();
        renderAs('ADMIN');

        const me = await rowOf('ada');
        expect(within(me).getByText('(you)')).toBeInTheDocument();
        expect(within(me).queryByRole('button', { name: /disable/i })).toBeNull();
        expect(within(me).queryByRole('button', { name: /make ada/i })).toBeNull();
        expect(within(me).getByRole('button', { name: 'Change the quota of ada' })).toBeInTheDocument();
    });

    it('disables and enables accounts, then shows them as they are now', async () => {
        const user = userEvent.setup();
        serve();
        api.put.mockResolvedValue({ data: {} });
        renderAs('ADMIN');

        await user.click(await screen.findByRole('button', { name: 'Disable bob' }));
        // The row's buttons wait while a change is under way.
        await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Enable eve' })).toBeEnabled());
        await user.click(screen.getByRole('button', { name: 'Enable eve' }));

        expect(api.put.mock.calls.map((call) => [call[0], params(call)])).toEqual([
            ['/api/admin/users/2/enabled', { enabled: 'false' }],
            ['/api/admin/users/3/enabled', { enabled: 'true' }],
        ]);
        expect(addToast).toHaveBeenCalledWith('bob is disabled', 'success');
        await vi.waitFor(() =>
            expect(api.get.mock.calls.filter(([url]) => url === '/api/admin/users')).toHaveLength(3),
        );
    });

    it('makes a user an admin, and says why when the server refuses', async () => {
        const user = userEvent.setup();
        serve();
        api.put.mockRejectedValueOnce({
            response: { status: 409, data: { message: 'There has to be at least one admin who can sign in' } },
        });
        renderAs('ADMIN');

        await user.click(await screen.findByRole('button', { name: 'Make bob an admin' }));

        expect(params(api.put.mock.calls[0])).toEqual({ role: 'ADMIN' });
        expect(addToast).toHaveBeenCalledWith('There has to be at least one admin who can sign in', 'error');
    });

    it('invites someone with a role and a quota in bytes, and shows the link once', async () => {
        const user = userEvent.setup();
        serve();
        api.post.mockResolvedValue({ data: { url: 'http://localhost/invite/abc', expiresAt: '2026-10-15T09:00:00Z' } });
        renderAs('ADMIN');

        await user.click(await screen.findByRole('button', { name: 'Invite someone' }));
        await user.type(screen.getByLabelText('Username'), ' dave ');
        await user.selectOptions(screen.getByLabelText('Role'), 'ADMIN');
        await user.type(screen.getByLabelText('Storage quota'), '1.5');
        await user.click(screen.getByRole('button', { name: 'Create invitation' }));

        expect(api.post.mock.calls[0][0]).toBe('/api/admin/invites');
        expect(params(api.post.mock.calls[0])).toEqual({
            username: 'dave',
            role: 'ADMIN',
            quota: String(1.5 * GB),
        });
        expect(await screen.findByLabelText('Invitation link')).toHaveValue('http://localhost/invite/abc');
    });

    it('refuses a quota that is not a positive number before asking the server', async () => {
        const user = userEvent.setup();
        serve();
        renderAs('ADMIN');

        await user.click(await screen.findByRole('button', { name: 'Invite someone' }));
        await user.type(screen.getByLabelText('Username'), 'dave');
        await user.type(screen.getByLabelText('Storage quota'), '0');
        await user.click(screen.getByRole('button', { name: 'Create invitation' }));

        expect(screen.getByRole('alert')).toHaveTextContent('more than 0');
        expect(api.post).not.toHaveBeenCalled();
    });

    it('changes a quota, starting from the current one, and clears it when left empty', async () => {
        const user = userEvent.setup();
        serve();
        api.put.mockResolvedValue({ data: {} });
        renderAs('ADMIN');

        await user.click(await screen.findByRole('button', { name: 'Change the quota of bob' }));
        const amount = screen.getByLabelText('Storage quota');
        expect(amount).toHaveValue(5);
        expect(screen.getByLabelText('Quota unit')).toHaveValue('GB');

        await user.clear(amount);
        await user.click(screen.getByRole('button', { name: 'Save' }));

        expect(api.put.mock.calls[0][0]).toBe('/api/admin/users/2/quota');
        expect(params(api.put.mock.calls[0])).toEqual({ quota: '' });
        expect(addToast).toHaveBeenCalledWith('Changed the quota of bob', 'success');
    });

    it('makes a password reset link and shows it', async () => {
        const user = userEvent.setup();
        serve();
        api.post.mockResolvedValue({
            data: { url: 'http://localhost/reset-password/xyz', expiresAt: '2026-10-09T09:00:00Z' },
        });
        renderAs('ADMIN');

        await user.click(await screen.findByRole('button', { name: 'Make a password reset link for bob' }));

        expect(api.post).toHaveBeenCalledWith('/api/admin/users/2/password-reset');
        expect(await screen.findByLabelText('Password reset link')).toHaveValue('http://localhost/reset-password/xyz');
    });

    it('lists open invitations and withdraws one', async () => {
        const user = userEvent.setup();
        serve();
        api.delete.mockResolvedValue({ data: {} });
        renderAs('ADMIN');

        const invitations = await screen.findByRole('region', { name: 'Invitations' });
        expect(within(invitations).getByText(/as user, 2 GB quota/)).toBeInTheDocument();
        await user.click(within(invitations).getByRole('button', { name: 'Withdraw the invitation for carol' }));

        expect(api.delete).toHaveBeenCalledWith('/api/admin/invites/9');
    });

    it('says so when there are no invitations', async () => {
        serve({ invites: [] });
        renderAs('ADMIN');

        expect(await screen.findByText('No invitation is waiting to be used.')).toBeInTheDocument();
    });

    it('shows where keyboard focus is', async () => {
        serve();
        const { container } = renderAs('ADMIN');
        await rowOf('bob');

        expect(controlsWithoutFocusIndicator(container)).toEqual([]);
    });
});
