import { compose, type StoreEnhancer, type UnknownAction } from '@reduxjs/toolkit';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError, AxiosHeaders } from 'axios';
import { Provider } from 'react-redux';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { loginUser, setupCompleted } from '../features/authSlice';
import AccountLink from '../pages/AccountLink';
import Admin from '../pages/Admin';
import Setup from '../pages/Setup';
import ShareModal from '../components/ShareModal';
import mockedApi from '../services/api';
import { makeStore } from './store';

// The admin page and the share dialog toast; the toasts aren't what these tests look at.
vi.mock('../hooks/useToast', () => ({ useToast: () => ({ addToast: vi.fn() }) }));

vi.mock('../services/api');
const api = vi.mocked(mockedApi);

// Redux Toolkit looks for the extension once, when it loads, so a hook has to be there before
// anything imports it. It hands each store to whichever stand-in the test installed, if any.
const devToolsHook = vi.hoisted(() => {
    const hook: { connect?: (options: unknown) => unknown } = {};
    (window as unknown as Record<string, unknown>).__REDUX_DEVTOOLS_EXTENSION_COMPOSE__ = (options: unknown) =>
        hook.connect ? hook.connect(options) : (...funcs: ((arg: unknown) => unknown)[]) =>
            funcs.reduceRight((composed, f) => (arg: unknown) => f(composed(arg)), (arg: unknown) => arg);
    return hook;
});

// Stands in for the Redux DevTools extension. Like the real one, it sits under the middleware, so it
// sees every action that reaches the reducers, a thunk's own included.
const installDevTools = () => {
    const seen: UnknownAction[] = [];
    const record: StoreEnhancer = (createStore) => (reducer, preloadedState) => {
        const store = createStore(reducer, preloadedState);
        const dispatch: typeof store.dispatch = (action) => {
            seen.push(action);
            return store.dispatch(action);
        };
        return { ...store, dispatch };
    };
    const connect = vi.fn(
        () =>
            (...enhancers: StoreEnhancer[]) =>
                (compose as (...funcs: StoreEnhancer[]) => StoreEnhancer)(...enhancers, record),
    );
    devToolsHook.connect = connect;
    return { connect, seen };
};

// Everything the Redux DevTools saw and the store holds, as text to search for a secret.
const everythingShown = (seen: UnknownAction[], state: unknown) => JSON.stringify({ seen, state });

// Opens an invitation or a password reset link's page, signed out, on a store the DevTools watch.
const openLink = (path: string) => {
    vi.stubEnv('DEV', true);
    const { seen } = installDevTools();
    const store = makeStore();
    render(
        <Provider store={store}>
            <MemoryRouter initialEntries={[path]}>
                <Routes>
                    <Route path="/invite/:token" element={<AccountLink purpose="invite" />} />
                    <Route path="/reset-password/:token" element={<AccountLink purpose="reset" />} />
                    <Route path="/login" element={<p>Sign-in page</p>} />
                </Routes>
            </MemoryRouter>
        </Provider>,
    );
    return { seen, store };
};

const choosePassword = async (password: string, submit: string) => {
    const user = userEvent.setup();
    await user.type(await screen.findByPlaceholderText('Password'), password);
    await user.type(screen.getByPlaceholderText('Confirm password'), password);
    await user.click(screen.getByRole('button', { name: submit }));
};

describe('store', () => {
    afterEach(() => {
        cleanup();
        devToolsHook.connect = undefined;
        vi.unstubAllEnvs();
        vi.resetAllMocks();
    });

    it('connects to the Redux DevTools in development', async () => {
        vi.stubEnv('DEV', true);
        const { connect } = installDevTools();

        makeStore();

        expect(connect).toHaveBeenCalled();
    });

    it('leaves the Redux DevTools alone in a production build', async () => {
        const { connect } = installDevTools();
        vi.stubEnv('DEV', false);

        makeStore();

        expect(connect).not.toHaveBeenCalled();
        // The same stand-in is reached in development, so the check above isn't passing only
        // because Redux Toolkit never saw it.
        vi.stubEnv('DEV', true);
        makeStore();
        expect(connect).toHaveBeenCalled();
    });

    it('shows the Redux DevTools no password during a sign-in', async () => {
        vi.stubEnv('DEV', true);
        const { seen } = installDevTools();
        const store = makeStore();
        api.post.mockResolvedValueOnce({ data: { username: 'ada', role: 'USER' } });
        // Shaped like a real axios failure: the request config, form body included, rides along on
        // both the error and its response.
        const config = { method: 'post', url: '/login', data: 'username=ada&password=wrong-secret', headers: new AxiosHeaders() };
        const response = { status: 401, statusText: 'Unauthorized', data: '', headers: {}, config };
        api.post.mockRejectedValueOnce(
            new AxiosError('Request failed with status code 401', 'ERR_BAD_REQUEST', config, null, response),
        );

        await store.dispatch(loginUser({ username: 'ada', password: 'hunter2-secret' }));
        await store.dispatch(loginUser({ username: 'ada', password: 'wrong-secret' }));

        const types = seen.map((action) => action.type);
        expect(types).toContain(loginUser.pending.type);
        expect(types).toContain(loginUser.fulfilled.type);
        expect(types).toContain(loginUser.rejected.type);
        const shown = JSON.stringify(seen);
        expect(shown).toContain('ada');
        expect(shown).toContain('Invalid username or password.');
        expect(shown).not.toContain('hunter2-secret');
        expect(shown).not.toContain('wrong-secret');
    });

    it('shows the Redux DevTools no setup code or password when setup completes', async () => {
        vi.stubEnv('DEV', true);
        const { seen } = installDevTools();
        const store = makeStore();
        api.post.mockResolvedValue({ data: { message: 'Setup successful' } });
        const user = userEvent.setup();
        render(
            <Provider store={store}>
                <MemoryRouter>
                    <Setup />
                </MemoryRouter>
            </Provider>,
        );

        await user.type(screen.getByLabelText('Setup code'), 'QWERT-YUIOP');
        await user.type(screen.getByPlaceholderText('Username'), 'ada');
        await user.type(screen.getByPlaceholderText('Password'), 'setup-secret-pw');
        await user.type(screen.getByPlaceholderText('Confirm password'), 'setup-secret-pw');
        await user.click(screen.getByRole('button', { name: 'Create account' }));

        await waitFor(() => expect(seen.map((action) => action.type)).toContain(setupCompleted.type));
        expect(api.post).toHaveBeenCalledWith('/setup', expect.anything(), expect.anything());
        const shown = everythingShown(seen, store.getState());
        expect(shown).not.toContain('QWERT-YUIOP');
        expect(shown).not.toContain('setup-secret-pw');
    });

    it('shows the Redux DevTools no password when an invitation is accepted, and the token only where the page reads the link', async () => {
        api.get.mockResolvedValue({ data: { username: 'carol', expiresAt: '2026-10-15T09:00:00Z' } });
        api.post.mockResolvedValue({ data: { message: 'Account created' } });
        const { seen, store } = openLink('/invite/invite-secret-token');

        await choosePassword('invite-secret-pw', 'Create account');

        expect(await screen.findByText('Sign-in page')).toBeInTheDocument();
        expect(api.post).toHaveBeenCalledWith('/api/invite/invite-secret-token', expect.anything(), expect.anything());
        expect(everythingShown(seen, store.getState())).not.toContain('invite-secret-pw');
        // The page asks what the link is for through the API slice, keyed by the token the address
        // bar already shows. Nothing else carries it.
        const withToken = seen.filter((action) => JSON.stringify(action).includes('invite-secret-token'));
        expect(withToken.length).toBeGreaterThan(0);
        for (const action of withToken) {
            expect(action).toMatchObject({ meta: { arg: { endpointName: 'describeInvite' } } });
        }
        expect(store.getState().api.mutations).toEqual({});
    });

    it('shows the Redux DevTools no password when a password reset is refused', async () => {
        api.get.mockResolvedValue({ data: { username: 'bob', expiresAt: '2026-10-15T09:00:00Z' } });
        api.post.mockRejectedValue({ response: { status: 400, data: { message: 'Password too common' } } });
        const { seen, store } = openLink('/reset-password/reset-secret-token');

        await choosePassword('reset-secret-pw', 'Set password');

        expect(await screen.findByText('Password too common')).toBeInTheDocument();
        expect(api.post).toHaveBeenCalledWith(
            '/api/reset-password/reset-secret-token',
            expect.anything(),
            expect.anything(),
        );
        expect(everythingShown(seen, store.getState())).not.toContain('reset-secret-pw');
        const withToken = seen.filter((action) => JSON.stringify(action).includes('reset-secret-token'));
        expect(withToken.length).toBeGreaterThan(0);
        for (const action of withToken) {
            expect(action).toMatchObject({ meta: { arg: { endpointName: 'describePasswordReset' } } });
        }
        expect(store.getState().api.mutations).toEqual({});
    });

    describe('links an admin or an owner makes (#436)', () => {
        // Opens the admin page, signed in as an admin, on a store the DevTools watch.
        const openAdmin = () => {
            vi.stubEnv('DEV', true);
            const { seen } = installDevTools();
            const store = makeStore();
            store.dispatch(loginUser.fulfilled({ username: 'ada', role: 'ADMIN' }, 'seed', 'ada'));
            api.get.mockImplementation(async (url: string) => ({
                data:
                    url === '/api/admin/users'
                        ? [{ id: 2, username: 'bob', role: 'USER', enabled: true, quotaBytes: null, usedBytes: 0 }]
                        : [],
            }));
            render(
                <Provider store={store}>
                    <MemoryRouter initialEntries={['/admin']}>
                        <Routes>
                            <Route path="/admin" element={<Admin />} />
                        </Routes>
                    </MemoryRouter>
                </Provider>,
            );
            return { seen, store };
        };
        const invitesAsked = () => api.get.mock.calls.filter(([url]) => url === '/api/admin/invites').length;

        it('shows the Redux DevTools no invitation link, and still lists the invitations again', async () => {
            const { seen, store } = openAdmin();
            api.post.mockResolvedValue({ data: { url: 'http://localhost/invite/invite-link-secret', expiresAt: '2026-12-01T00:00:00Z' } });
            const user = userEvent.setup();
            await user.click(await screen.findByRole('button', { name: 'Invite someone' }));
            await user.type(screen.getByLabelText('Username'), 'dave');
            const before = invitesAsked();

            await user.click(screen.getByRole('button', { name: 'Create invitation' }));

            expect(await screen.findByLabelText('Invitation link')).toHaveValue('http://localhost/invite/invite-link-secret');
            await waitFor(() => expect(invitesAsked()).toBe(before + 1));
            expect(everythingShown(seen, store.getState())).not.toContain('invite-link-secret');
        });

        it('shows the Redux DevTools no password reset link', async () => {
            const { seen, store } = openAdmin();
            api.post.mockResolvedValue({ data: { url: 'http://localhost/reset-password/reset-link-secret', expiresAt: '2026-12-01T00:00:00Z' } });
            const user = userEvent.setup();

            await user.click(await screen.findByRole('button', { name: 'Reset password for bob' }));

            expect(await screen.findByLabelText('Password reset link')).toHaveValue('http://localhost/reset-password/reset-link-secret');
            expect(everythingShown(seen, store.getState())).not.toContain('reset-link-secret');
        });

        it('shows the Redux DevTools no share link, and still lists the item\'s links again', async () => {
            vi.stubEnv('DEV', true);
            const { seen } = installDevTools();
            const store = makeStore();
            api.get.mockResolvedValue({ data: [] });
            api.post.mockResolvedValue({ data: { url: 'http://localhost/share/share-link-secret', expiresAt: '2026-12-01T00:00:00Z' } });
            const sharesAsked = () => api.get.mock.calls.filter(([url]) => url === '/api/share').length;
            const user = userEvent.setup();
            render(
                <Provider store={store}>
                    <ShareModal isOpen onClose={() => {}} item={{ name: 'a.txt', path: 'docs/a.txt', isDirectory: false }} />
                </Provider>,
            );
            await waitFor(() => expect(sharesAsked()).toBe(1));

            await user.click(screen.getByRole('button', { name: 'Generate link' }));

            expect(await screen.findByLabelText('Share link')).toHaveValue('http://localhost/share/share-link-secret');
            await waitFor(() => expect(sharesAsked()).toBe(2));
            expect(everythingShown(seen, store.getState())).not.toContain('share-link-secret');
        });
    });
});
