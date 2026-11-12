import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import Navbar from './Navbar';
import { loginUser } from '../features/authSlice';
import { makeStore } from '../redux/store';
import mockedApi from '../services/api';

vi.mock('../services/api');
const api = vi.mocked(mockedApi);

const addToast = vi.fn();
vi.mock('../hooks/useToast', () => ({
    useToast: () => ({ addToast }),
}));

const renderSignedIn = () => {
    const store = makeStore();
    store.dispatch(loginUser.fulfilled({ username: 'ada', role: null }, 'seed', { username: 'ada', password: '' }));
    render(
        <Provider store={store}>
            <Navbar onMenuClick={vi.fn()} />
        </Provider>,
    );
    return store;
};

describe('Navbar', () => {
    afterEach(() => {
        cleanup();
        vi.resetAllMocks();
        localStorage.clear();
    });

    it('signs out when the server ends the session', async () => {
        api.post.mockResolvedValueOnce({});
        const user = userEvent.setup();
        const store = renderSignedIn();

        await user.click(screen.getByRole('button', { name: 'Logout' }));

        await waitFor(() => expect(store.getState().auth.isAuthenticated).toBe(false));
        expect(addToast).not.toHaveBeenCalled();
    });

    it('stays signed in and says so when the logout fails', async () => {
        api.post.mockRejectedValueOnce({ response: { status: 403, data: '' } });
        const user = userEvent.setup();
        const store = renderSignedIn();

        await user.click(screen.getByRole('button', { name: 'Logout' }));

        await waitFor(() => expect(addToast).toHaveBeenCalledWith('Logout failed. Please try again.', 'error'));
        expect(store.getState().auth).toMatchObject({ isAuthenticated: true, user: 'ada' });
    });
});
