import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router-dom';
import type { UnknownAction } from '@reduxjs/toolkit';
import Login from './Login';
import { fetchCurrentUser, loginUser, SETUP_REQUIRED } from '../features/authSlice';
import mockedApi from '../services/api';
import { makeStore } from '../redux/store';

vi.mock('../services/api');
const api = vi.mocked(mockedApi);

// Seeds the outcome of the session check App runs before anything is shown.
const renderAfterSessionCheck = (payload: string, ...laterActions: UnknownAction[]) => {
    const store = makeStore();
    store.dispatch(fetchCurrentUser.rejected(null, 'startup', undefined, payload));
    laterActions.forEach((action) => store.dispatch(action));
    render(
        <Provider store={store}>
            <MemoryRouter>
                <Login />
            </MemoryRouter>
        </Provider>,
    );
};

describe('Login', () => {
    afterEach(cleanup);
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('shows the public demo\'s account and fills it in', async () => {
        const user = userEvent.setup();
        api.get.mockResolvedValue({ data: { username: 'demo', password: 'javadropbox' } });
        renderAfterSessionCheck('Not authenticated');

        await user.click(await screen.findByRole('button', { name: /fill in the demo account/i }));

        expect(api.get).toHaveBeenCalledWith('/api/demo', expect.anything());
        expect(screen.getByPlaceholderText('Username')).toHaveValue('demo');
        expect(screen.getByPlaceholderText('Password')).toHaveValue('javadropbox');
    });

    it('says nothing about a demo on an ordinary install', async () => {
        api.get.mockRejectedValue({ response: { status: 404 } });
        renderAfterSessionCheck('Not authenticated');

        await vi.waitFor(() => expect(api.get).toHaveBeenCalledWith('/api/demo', expect.anything()));
        expect(screen.queryByText(/public demo/i)).not.toBeInTheDocument();
    });

    it('offers first-user setup while no account exists', () => {
        renderAfterSessionCheck(SETUP_REQUIRED);

        expect(screen.getByRole('link', { name: /set up the first user/i })).toHaveAttribute('href', '/setup');
    });

    it('does not offer setup once an account exists', () => {
        renderAfterSessionCheck('Your session has expired. Please sign in again.');

        expect(screen.queryByRole('link', { name: /set ?up/i })).not.toBeInTheDocument();
    });

    it('shows what the page that sent the user here had to say', () => {
        const store = makeStore();
        store.dispatch(fetchCurrentUser.rejected(null, 'startup', undefined, 'Not authenticated'));
        render(
            <Provider store={store}>
                <MemoryRouter initialEntries={[{ pathname: '/login', state: { notice: 'Your account is ready.' } }]}>
                    <Login />
                </MemoryRouter>
            </Provider>,
        );

        expect(screen.getByRole('status')).toHaveTextContent('Your account is ready.');
    });

    it('announces a failed sign-in to screen readers', () => {
        renderAfterSessionCheck(
            'Not authenticated',
            loginUser.rejected(null, 'submit', { username: 'ada', password: '' }, 'Invalid username or password.'),
        );

        expect(screen.getByRole('alert')).toHaveTextContent('Invalid username or password.');
    });
});
