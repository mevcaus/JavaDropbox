import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { configureStore } from '@reduxjs/toolkit';
import { Provider } from 'react-redux';
import Setup from './Setup';
import authReducer, { fetchCurrentUser, SETUP_REQUIRED } from '../features/authSlice';
import api from '../services/api';

vi.mock('../services/api');

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => ({
    ...(await importOriginal()),
    useNavigate: () => mockNavigate,
}));

// Starts where App leaves a fresh install: the session check found setup pending.
const renderSetup = () => {
    const store = configureStore({ reducer: { auth: authReducer } });
    store.dispatch(fetchCurrentUser.rejected(null, 'startup', undefined, SETUP_REQUIRED));
    render(
        <Provider store={store}>
            <MemoryRouter>
                <Setup />
            </MemoryRouter>
        </Provider>,
    );
    return store;
};

const fillForm = async (user, { code = 'ABCDE-FGHJK', username = 'ada', password = 'correct horse', confirm = password } = {}) => {
    await user.type(screen.getByLabelText('Setup code'), code);
    await user.type(screen.getByPlaceholderText('Username'), username);
    await user.type(screen.getByPlaceholderText('Password'), password);
    await user.type(screen.getByPlaceholderText('Confirm password'), confirm);
    await user.click(screen.getByRole('button', { name: 'Create account' }));
};

describe('Setup', () => {
    afterEach(() => {
        cleanup();
        vi.clearAllMocks();
    });

    it('sends the setup code with the new account and moves on to sign in', async () => {
        api.post.mockResolvedValueOnce({ data: { message: 'Setup successful' } });
        const user = userEvent.setup();
        const store = renderSetup();

        await fillForm(user);

        const params = api.post.mock.calls[0][1];
        expect(api.post.mock.calls[0][0]).toBe('/setup');
        expect(Object.fromEntries(params)).toEqual({
            code: 'ABCDE-FGHJK',
            username: 'ada',
            password: 'correct horse',
        });
        expect(mockNavigate).toHaveBeenCalledWith('/login', { replace: true });
        // So the sign-in page stops offering setup
        expect(store.getState().auth.setupRequired).toBe(false);
    });

    it('does not submit when the passwords differ', async () => {
        const user = userEvent.setup();
        renderSetup();

        await fillForm(user, { confirm: 'something else' });

        expect(api.post).not.toHaveBeenCalled();
        expect(screen.getByText('The passwords do not match.')).toBeInTheDocument();
    });

    it('does not submit a password shorter than the minimum', async () => {
        const user = userEvent.setup();
        renderSetup();

        await fillForm(user, { password: 'short' });

        expect(api.post).not.toHaveBeenCalled();
        expect(screen.getByText('Password must be at least 8 characters.')).toBeInTheDocument();
    });

    it('shows the server message when the code is wrong', async () => {
        api.post.mockRejectedValueOnce({
            response: { status: 403, data: { message: 'Incorrect setup code. It is printed in the server log.' } },
        });
        const user = userEvent.setup();
        renderSetup();

        await fillForm(user);

        expect(await screen.findByText('Incorrect setup code. It is printed in the server log.')).toBeInTheDocument();
        expect(mockNavigate).not.toHaveBeenCalled();
    });
});
