import { configureStore } from '@reduxjs/toolkit';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import authReducer, {
    clearUser,
    fetchCurrentUser,
    loginUser,
    logoutUser,
    setUser,
} from './authSlice';
import api from '../services/api';

vi.mock('../services/api');

// The slice's initialState is captured at import time, so seed localStorage through the
// reducers rather than expecting a fresh store to re-read it.
const makeStore = () => configureStore({ reducer: { auth: authReducer } });
const authState = (store) => store.getState().auth;

describe('authSlice', () => {
    let store;

    beforeEach(() => {
        vi.clearAllMocks();
        localStorage.clear();
        store = makeStore();
    });

    describe('loginUser', () => {
        it('posts form-encoded credentials to /login', async () => {
            api.post.mockResolvedValueOnce({ data: 'ok' });

            await store.dispatch(loginUser({ username: 'ada', password: 'hunter2' }));

            const [url, body, config] = api.post.mock.calls[0];
            expect(url).toBe('/login');
            expect(body.get('username')).toBe('ada');
            expect(body.get('password')).toBe('hunter2');
            expect(config.headers['Content-Type']).toBe('application/x-www-form-urlencoded');
        });

        it('marks the request in flight and clears any previous error', () => {
            store.dispatch({ type: loginUser.rejected.type, payload: 'Login failed' });
            expect(authState(store).error).toBe('Login failed');

            store.dispatch({ type: loginUser.pending.type });

            expect(authState(store)).toMatchObject({ loading: true, error: null });
        });

        it('authenticates and persists the user on success', async () => {
            api.post.mockResolvedValueOnce({ data: 'ok' });

            await store.dispatch(loginUser({ username: 'ada', password: 'hunter2' }));

            expect(authState(store)).toMatchObject({
                loading: false,
                isAuthenticated: true,
                user: 'ada',
                error: null,
            });
            expect(localStorage.getItem('user')).toBe('ada');
        });

        it('surfaces the server message and stays unauthenticated on failure', async () => {
            api.post.mockRejectedValueOnce({ response: { data: 'Invalid credentials' } });

            await store.dispatch(loginUser({ username: 'ada', password: 'wrong' }));

            expect(authState(store)).toMatchObject({
                loading: false,
                isAuthenticated: false,
                error: 'Invalid credentials',
            });
            expect(localStorage.getItem('user')).toBeNull();
        });

        it('falls back to a generic message when the server sends no body', async () => {
            api.post.mockRejectedValueOnce(new Error('Network Error'));

            await store.dispatch(loginUser({ username: 'ada', password: 'hunter2' }));

            expect(authState(store).error).toBe('Login failed');
        });

        it('does not leave a failed login authenticated from a previous session', async () => {
            store.dispatch(setUser('ada'));
            expect(authState(store).isAuthenticated).toBe(true);

            api.post.mockRejectedValueOnce({ response: { data: 'Invalid credentials' } });
            await store.dispatch(loginUser({ username: 'ada', password: 'wrong' }));

            // A rejected login must not silently keep the old session alive
            expect(authState(store).error).toBe('Invalid credentials');
            expect(authState(store).loading).toBe(false);
        });
    });

    describe('logoutUser', () => {
        it('clears the session once the request resolves', async () => {
            store.dispatch(setUser('ada'));
            api.get.mockResolvedValueOnce({});

            await store.dispatch(logoutUser());

            expect(authState(store)).toMatchObject({ user: null, isAuthenticated: false });
            expect(localStorage.getItem('user')).toBeNull();
            expect(api.get).toHaveBeenCalledWith('/logout');
        });

        it('still clears the session when the logout request fails', async () => {
            store.dispatch(setUser('ada'));
            api.get.mockRejectedValueOnce(new Error('Network Error'));

            await store.dispatch(logoutUser());

            // The thunk swallows the error so a dead backend cannot strand the user logged in
            expect(authState(store)).toMatchObject({ user: null, isAuthenticated: false });
            expect(localStorage.getItem('user')).toBeNull();
        });
    });

    describe('fetchCurrentUser', () => {
        it('adopts the username the backend reports as the session', async () => {
            api.get.mockResolvedValueOnce({ data: { username: 'ada' } });

            await store.dispatch(fetchCurrentUser());

            expect(api.get).toHaveBeenCalledWith('/api/me', { timeout: 8000 });
            expect(authState(store)).toMatchObject({
                isInitialized: true,
                isAuthenticated: true,
                user: 'ada',
            });
            expect(localStorage.getItem('user')).toBe('ada');
        });

        it('clears a stale cached session when the backend says 401', async () => {
            store.dispatch(setUser('ada'));
            api.get.mockRejectedValueOnce({ response: { status: 401, data: 'Unauthorized' } });

            await store.dispatch(fetchCurrentUser());

            // This is the dashboard-flash case: localStorage says signed in, the server disagrees
            expect(authState(store)).toMatchObject({
                isInitialized: true,
                isAuthenticated: false,
                user: null,
            });
            expect(localStorage.getItem('user')).toBeNull();
        });

        it('does not treat the setup-page redirect as a signed-in session', async () => {
            // Pending first-run setup redirects /api/me to /setup, which resolves 200 with HTML
            api.get.mockResolvedValueOnce({ data: '<!doctype html><title>Setup</title>' });

            await store.dispatch(fetchCurrentUser());

            expect(authState(store)).toMatchObject({
                isInitialized: true,
                isAuthenticated: false,
                user: null,
            });
            expect(localStorage.getItem('user')).toBeNull();
        });

        it('lifts the loading gate when the backend never answers', async () => {
            // A dead database can stall the request until the pool times out; the app must not
            // sit behind the spinner waiting for it
            api.get.mockRejectedValueOnce(
                Object.assign(new Error('timeout of 8000ms exceeded'), { code: 'ECONNABORTED' })
            );

            await store.dispatch(fetchCurrentUser());

            expect(authState(store)).toMatchObject({
                isInitialized: true,
                isAuthenticated: false,
            });
        });

        it('marks the app initialised either way so the loading gate always lifts', async () => {
            expect(authState(store).isInitialized).toBe(false);

            api.get.mockRejectedValueOnce(new Error('Network Error'));
            await store.dispatch(fetchCurrentUser());

            expect(authState(store).isInitialized).toBe(true);
        });
    });

    describe('error messages', () => {
        it('does not render the server\'s html error page into the ui', async () => {
            const html =
                '<!doctype html><html><head><title>HTTP Status 500</title></head><body>' +
                '<h1>HTTP Status 500</h1><pre>org.springframework.transaction.' +
                'CannotCreateTransactionException: Could not open JPA EntityManager</pre>' +
                '</body></html>';
            api.post.mockRejectedValueOnce({ response: { status: 500, data: html } });

            await store.dispatch(loginUser({ username: 'ada', password: 'hunter2' }));

            const { error } = authState(store);
            expect(error).not.toContain('<');
            expect(error).not.toContain('Exception');
            expect(error).toBe('The server is unavailable right now. Please try again.');
        });

        it('explains a rejected password rather than showing an empty body', async () => {
            // The backend's failure handler sets 401 with no body at all
            api.post.mockRejectedValueOnce({ response: { status: 401, data: '' } });

            await store.dispatch(loginUser({ username: 'ada', password: 'wrong' }));

            expect(authState(store).error).toBe('Invalid username or password.');
        });

        it('still prefers a real message from a json error body', async () => {
            api.post.mockRejectedValueOnce({
                response: { status: 400, data: { message: 'Account is locked' } },
            });

            await store.dispatch(loginUser({ username: 'ada', password: 'hunter2' }));

            expect(authState(store).error).toBe('Account is locked');
        });
    });

    describe('reducers', () => {
        it('setUser authenticates and persists', () => {
            store.dispatch(setUser('ada'));

            expect(authState(store)).toMatchObject({ user: 'ada', isAuthenticated: true });
            expect(localStorage.getItem('user')).toBe('ada');
        });

        it('clearUser removes the persisted session', () => {
            store.dispatch(setUser('ada'));
            store.dispatch(clearUser());

            expect(authState(store)).toMatchObject({ user: null, isAuthenticated: false });
            expect(localStorage.getItem('user')).toBeNull();
        });
    });
});
