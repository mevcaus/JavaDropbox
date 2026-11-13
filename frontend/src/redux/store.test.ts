import { compose, type StoreEnhancer, type UnknownAction } from '@reduxjs/toolkit';
import { AxiosError, AxiosHeaders } from 'axios';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { loginUser } from '../features/authSlice';
import mockedApi from '../services/api';
import { makeStore } from './store';

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

describe('store', () => {
    afterEach(() => {
        devToolsHook.connect = undefined;
        vi.unstubAllEnvs();
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
});
