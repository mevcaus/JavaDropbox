import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { configureStore } from '@reduxjs/toolkit';
import { Provider } from 'react-redux';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import AccountLink from './AccountLink';
import authReducer, { fetchCurrentUser } from '../features/authSlice';
import api from '../services/api';

vi.mock('../services/api');

const INFO = { username: 'carol', expiresAt: '2026-10-15T09:00:00Z' };

// Where the page sends someone once the link is used, with what it tells them.
const SignIn = () => <p>Sign-in page: {useLocation().state?.notice}</p>;

// Opens the page as App would, after its session check: signed out, or signed in as signedInAs.
const renderAt = (path, { signedInAs } = {}) => {
    const store = configureStore({ reducer: { auth: authReducer } });
    store.dispatch(
        signedInAs
            ? fetchCurrentUser.fulfilled({ username: signedInAs, role: 'ADMIN' }, 'startup')
            : fetchCurrentUser.rejected(null, 'startup', undefined, 'Not authenticated'),
    );
    render(
        <Provider store={store}>
            <MemoryRouter initialEntries={[path]}>
                <Routes>
                    <Route path="/invite/:token" element={<AccountLink purpose="invite" />} />
                    <Route path="/reset-password/:token" element={<AccountLink purpose="reset" />} />
                    <Route path="/login" element={<SignIn />} />
                </Routes>
            </MemoryRouter>
        </Provider>,
    );
    return store;
};

const choose = async (user, password, confirmation = password) => {
    await user.type(await screen.findByPlaceholderText('Password'), password);
    await user.type(screen.getByPlaceholderText('Confirm password'), confirmation);
};

describe('AccountLink', () => {
    afterEach(cleanup);
    beforeEach(() => vi.clearAllMocks());

    it('says whose account an invitation creates, and creates it with the chosen password', async () => {
        const user = userEvent.setup();
        api.get.mockResolvedValue({ data: INFO });
        api.post.mockResolvedValue({ data: {} });
        renderAt('/invite/tok-1');

        expect(await screen.findByRole('heading', { name: 'Create your account' })).toBeInTheDocument();
        expect(screen.getByText('carol')).toBeInTheDocument();
        expect(api.get).toHaveBeenCalledWith('/invite/tok-1/info');

        await choose(user, 'long enough');
        await user.click(screen.getByRole('button', { name: 'Create account' }));

        expect(api.post.mock.calls[0][0]).toBe('/invite/tok-1');
        expect(Object.fromEntries(api.post.mock.calls[0][1])).toEqual({ password: 'long enough' });
        expect(await screen.findByText(/Sign-in page: Your account is ready/)).toBeInTheDocument();
    });

    it('sets a new password from a reset link', async () => {
        const user = userEvent.setup();
        api.get.mockResolvedValue({ data: { ...INFO, username: 'bob' } });
        api.post.mockResolvedValue({ data: {} });
        renderAt('/reset-password/tok-2');

        expect(await screen.findByRole('heading', { name: 'Choose a new password' })).toBeInTheDocument();
        await choose(user, 'a new password');
        await user.click(screen.getByRole('button', { name: 'Set password' }));

        expect(api.get).toHaveBeenCalledWith('/reset-password/tok-2/info');
        expect(api.post.mock.calls[0][0]).toBe('/reset-password/tok-2');
        expect(await screen.findByText(/Sign-in page: Your password has been changed/)).toBeInTheDocument();
    });

    it('checks the password before sending it', async () => {
        const user = userEvent.setup();
        api.get.mockResolvedValue({ data: INFO });
        renderAt('/invite/tok-1');

        await choose(user, 'short');
        await user.click(await screen.findByRole('button', { name: 'Create account' }));
        expect(screen.getByRole('alert')).toHaveTextContent('at least 8 characters');

        await user.clear(screen.getByPlaceholderText('Password'));
        await user.clear(screen.getByPlaceholderText('Confirm password'));
        await choose(user, 'long enough', 'long enougH');
        await user.click(screen.getByRole('button', { name: 'Create account' }));
        expect(screen.getByRole('alert')).toHaveTextContent('do not match');

        expect(api.post).not.toHaveBeenCalled();
    });

    it('says when a link has been used or has expired', async () => {
        api.get.mockRejectedValue({ response: { status: 404, data: { message: 'gone' } } });
        renderAt('/invite/used');

        expect(await screen.findByRole('alert')).toHaveTextContent('expired or has already been used');
        expect(screen.queryByPlaceholderText('Password')).toBeNull();
        expect(screen.getByRole('link', { name: 'Go to sign-in' })).toBeInTheDocument();
    });

    it('refuses a description that is not one, such as the app\'s HTML from a misrouted request', async () => {
        api.get.mockResolvedValue({ data: '<!doctype html><html></html>' });
        renderAt('/invite/tok-1');

        expect(await screen.findByRole('alert')).toHaveTextContent('Could not open this link.');
        expect(screen.queryByPlaceholderText('Password')).toBeNull();
    });

    it('says it signs out whoever is signed in, and does once the account exists', async () => {
        const user = userEvent.setup();
        api.get.mockResolvedValue({ data: INFO });
        api.post.mockResolvedValue({ data: {} });
        const store = renderAt('/invite/tok-1', { signedInAs: 'ada' });

        expect(await screen.findByText(/You are signed in as/)).toHaveTextContent(
            'You are signed in as ada. Creating the account signs you out here',
        );
        await choose(user, 'long enough');
        await user.click(screen.getByRole('button', { name: 'Create account' }));

        // Without signing out, the sign-in page would send ada straight back to her own files.
        expect(await screen.findByText(/Sign-in page: Your account is ready/)).toBeInTheDocument();
        expect(api.post.mock.calls.map(([url]) => url)).toEqual(['/invite/tok-1', '/logout']);
        expect(store.getState().auth.isAuthenticated).toBe(false);
    });

    it('still signs out here when the server has already ended the session', async () => {
        const user = userEvent.setup();
        api.get.mockResolvedValue({ data: { ...INFO, username: 'ada' } });
        api.post
            .mockResolvedValueOnce({ data: {} })
            // The reset of her own password ended the session, so logout finds none.
            .mockRejectedValueOnce({ response: { status: 401 } });
        const store = renderAt('/reset-password/tok-2', { signedInAs: 'ada' });

        await choose(user, 'a new password');
        await user.click(await screen.findByRole('button', { name: 'Set password' }));

        expect(await screen.findByText(/Sign-in page: Your password has been changed/)).toBeInTheDocument();
        expect(store.getState().auth.isAuthenticated).toBe(false);
    });

    it('says when the link stopped working after the page opened', async () => {
        const user = userEvent.setup();
        api.get.mockResolvedValue({ data: INFO });
        api.post.mockRejectedValue({
            response: { status: 404, data: { message: 'This link has expired or has already been used' } },
        });
        renderAt('/invite/tok-1');

        await choose(user, 'long enough');
        await user.click(screen.getByRole('button', { name: 'Create account' }));

        expect(await screen.findByRole('alert')).toHaveTextContent('expired or has already been used');
    });

    it('shows the server\'s reason when it refuses the password', async () => {
        const user = userEvent.setup();
        api.get.mockResolvedValue({ data: INFO });
        api.post.mockRejectedValue({
            response: { status: 409, data: { message: 'There is already an account called "carol"' } },
        });
        renderAt('/invite/tok-1');

        await choose(user, 'long enough');
        await user.click(screen.getByRole('button', { name: 'Create account' }));

        expect(await screen.findByRole('alert')).toHaveTextContent('already an account called "carol"');
        expect(screen.getByRole('button', { name: 'Create account' })).toBeEnabled();
    });
});
