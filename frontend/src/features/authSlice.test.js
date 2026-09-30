import { AxiosError } from 'axios';
import { configureStore } from '@reduxjs/toolkit';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import authReducer, {
    clearUser,
    fetchCurrentUser,
    loginUser,
    logoutUser,
    setupCompleted,
} from './authSlice';
import api from '../services/api';

vi.mock('../services/api');

// The slice's initialState is captured at import time, so seed localStorage through the
// reducers rather than expecting a fresh store to re-read it.
const makeStore = () => configureStore({ reducer: { auth: authReducer } });
const authState = (store) => store.getState().auth;
// Seeds a signed-in session the way a successful login leaves it.
const signIn = (store, username) => store.dispatch(loginUser.fulfilled({ username }, 'seed'));

// Whether a secret appears anywhere in a value, however deeply nested (cycle-safe).
const mentions = (value, secret, seen = new WeakSet()) => {
    if (typeof value === 'string') return value.includes(secret);
    if (value instanceof URLSearchParams) return value.toString().includes(secret);
    if (value === null || typeof value !== 'object' || seen.has(value)) return false;
    seen.add(value);
    return Object.values(value).some((child) => mentions(child, secret, seen));
};

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

        describe('console output', () => {
            const methods = ['log', 'info', 'warn', 'error', 'debug'];
            afterEach(() => vi.restoreAllMocks());

            it('never contains the submitted password', async () => {
                const spies = methods.map((method) => vi.spyOn(console, method).mockImplementation(() => {}));
                // Shaped like a real axios failure: the request config, body included, rides along
                // on both the error and its response.
                const config = { method: 'post', url: '/login', data: 'username=ada&password=hunter2-secret' };
                const response = { status: 401, statusText: 'Unauthorized', data: '', headers: {}, config };
                api.post.mockRejectedValueOnce(
                    new AxiosError('Request failed with status code 401', 'ERR_BAD_REQUEST', config, null, response),
                );

                await store.dispatch(loginUser({ username: 'ada', password: 'hunter2-secret' }));

                expect(authState(store).error).toBe('Invalid username or password.');
                const logged = spies.flatMap((spy) => spy.mock.calls);
                expect(mentions(logged, 'hunter2-secret')).toBe(false);
            });
        });

        it('does not leave a failed login authenticated from a previous session', async () => {
            signIn(store, 'ada');
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
            signIn(store, 'ada');
            api.post.mockResolvedValueOnce({});

            await store.dispatch(logoutUser());

            expect(authState(store)).toMatchObject({ user: null, isAuthenticated: false });
            expect(localStorage.getItem('user')).toBeNull();
            // POST, not GET: the backend only ends the session for a POST
            expect(api.post).toHaveBeenCalledWith('/logout');
            expect(api.get).not.toHaveBeenCalled();
        });

        // The server session may still be alive after any of these, so showing the user as signed
        // out would leave the next person at the machine signed in as them.
        it.each([
            ['a 403 (stale CSRF token)', { response: { status: 403, data: '' } }],
            ['a network error', new Error('Network Error')],
            ['a 500', { response: { status: 500, data: '' } }],
        ])('rejects and keeps the session after %s', async (_label, failure) => {
            signIn(store, 'ada');
            api.post.mockRejectedValueOnce(failure);

            const action = await store.dispatch(logoutUser());

            expect(action.type).toBe(logoutUser.rejected.type);
            expect(authState(store)).toMatchObject({ user: 'ada', isAuthenticated: true });
            expect(localStorage.getItem('user')).toBe('ada');
        });

        it('treats a 401 as signed out, since the session is already gone', async () => {
            signIn(store, 'ada');
            api.post.mockRejectedValueOnce({ response: { status: 401, data: '' } });

            const action = await store.dispatch(logoutUser());

            expect(action.type).toBe(logoutUser.fulfilled.type);
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
            signIn(store, 'ada');
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
            // Pending first-run setup redirects /api/me to /setup; the browser follows it and the
            // request resolves 200 with the app's HTML
            api.get.mockResolvedValueOnce({
                data: '<!doctype html><title>Setup</title>',
                request: { responseURL: 'http://localhost:5173/setup' },
            });

            await store.dispatch(fetchCurrentUser());

            expect(authState(store)).toMatchObject({
                isInitialized: true,
                isAuthenticated: false,
                user: null,
            });
            expect(localStorage.getItem('user')).toBeNull();
        });

        describe('first-run setup', () => {
            it('is required when the session check was redirected to the setup page', async () => {
                api.get.mockResolvedValueOnce({
                    data: '<!doctype html><title>Setup</title>',
                    request: { responseURL: 'http://localhost:5173/setup' },
                });

                await store.dispatch(fetchCurrentUser());

                expect(authState(store).setupRequired).toBe(true);
            });

            it('is not required when the backend answers 401: an account exists', async () => {
                api.get.mockRejectedValueOnce({ response: { status: 401, data: '' } });

                await store.dispatch(fetchCurrentUser());

                expect(authState(store).setupRequired).toBe(false);
            });

            it('is not assumed from some other page that is not a session', async () => {
                // e.g. a proxy's error page: no session, but no sign that setup is pending either
                api.get.mockResolvedValueOnce({
                    data: '<!doctype html><title>Bad gateway</title>',
                    request: { responseURL: 'http://localhost:5173/api/me' },
                });

                await store.dispatch(fetchCurrentUser());

                expect(authState(store)).toMatchObject({ isAuthenticated: false, setupRequired: false });
            });

            it('is no longer required once the first account is created', async () => {
                api.get.mockResolvedValueOnce({
                    data: '<!doctype html><title>Setup</title>',
                    request: { responseURL: 'http://localhost:5173/setup' },
                });
                await store.dispatch(fetchCurrentUser());

                store.dispatch(setupCompleted());

                expect(authState(store).setupRequired).toBe(false);
            });
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
        it('clearUser removes the persisted session', () => {
            signIn(store, 'ada');
            store.dispatch(clearUser());

            expect(authState(store)).toMatchObject({ user: null, isAuthenticated: false });
            expect(localStorage.getItem('user')).toBeNull();
        });
    });
});
