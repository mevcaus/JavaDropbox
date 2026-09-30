import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { configureStore } from '@reduxjs/toolkit';
import { Provider } from 'react-redux';
import App from './App';
import authReducer from './features/authSlice';
import filesReducer from './features/filesSlice';
import api from './services/api';

vi.mock('./services/api');

// App owns a BrowserRouter, so it is steered through the real history.
const renderAppAt = (url) => {
    window.history.pushState({}, '', url);
    const store = configureStore({ reducer: { auth: authReducer, files: filesReducer } });
    render(
        <Provider store={store}>
            <App />
        </Provider>,
    );
};

describe('App routing', () => {
    beforeEach(() => {
        localStorage.clear();
    });

    afterEach(() => {
        cleanup();
        vi.resetAllMocks();
    });

    it('sends an unknown URL to the dashboard instead of a blank page', async () => {
        const tree = [{ name: 'report.pdf', isDirectory: false, size: 1, lastModified: '2026-01-01T00:00:00Z', relativePath: 'report.pdf' }];
        api.get.mockImplementation(async (url) => (url === '/api/me' ? { data: { username: 'ada' } } : { data: tree }));

        renderAppAt('/no-such-page');

        await waitFor(() => expect(window.location.pathname).toBe('/dashboard'));
        expect(await screen.findByText('report.pdf', { selector: '.font-medium' })).toBeInTheDocument();
        expect(screen.getByRole('heading', { name: 'My Files' })).toBeInTheDocument();
    });

    it('sends an unknown URL on to sign in when there is no session', async () => {
        api.get.mockRejectedValue({ response: { status: 401, data: '' } });

        renderAppAt('/no-such-page');

        await waitFor(() => expect(window.location.pathname).toBe('/login'));
        expect(screen.getByRole('heading', { name: 'Sign in to your account' })).toBeInTheDocument();
    });
});
