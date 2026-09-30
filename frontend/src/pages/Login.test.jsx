import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { configureStore } from '@reduxjs/toolkit';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router-dom';
import Login from './Login';
import authReducer, { fetchCurrentUser, SETUP_REQUIRED } from '../features/authSlice';

vi.mock('../services/api');

// Seeds the outcome of the session check App runs before anything is shown.
const renderAfterSessionCheck = (payload) => {
    const store = configureStore({ reducer: { auth: authReducer } });
    store.dispatch(fetchCurrentUser.rejected(null, 'startup', undefined, payload));
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

    it('offers first-user setup while no account exists', () => {
        renderAfterSessionCheck(SETUP_REQUIRED);

        expect(screen.getByRole('link', { name: /set up the first user/i })).toHaveAttribute('href', '/setup');
    });

    it('does not offer setup once an account exists', () => {
        renderAfterSessionCheck('Your session has expired. Please sign in again.');

        expect(screen.queryByRole('link', { name: /set ?up/i })).not.toBeInTheDocument();
    });
});
