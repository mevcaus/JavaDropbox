import axios, { AxiosError } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import api, { setUnauthorizedHandler } from './api';

const clearCookies = () => {
    document.cookie.split('; ').forEach((cookie) => {
        const name = cookie.split('=')[0];
        if (name) {
            document.cookie = `${name}=; Max-Age=0; path=/`;
        }
    });
};

const defaultAdapter = api.defaults.adapter;

afterEach(() => {
    api.defaults.adapter = defaultAdapter;
    setUnauthorizedHandler(() => {});
});

describe('api csrf priming', () => {
    let adapter;

    beforeEach(() => {
        vi.restoreAllMocks();
        clearCookies();
        adapter = vi.fn(async (config) => ({
            data: '',
            status: 200,
            statusText: 'OK',
            headers: {},
            config,
        }));
        api.defaults.adapter = adapter;
    });

    it('fetches a token before a state-changing request when no cookie is present', async () => {
        // Without this the very first POST -- login -- goes out with no token and is rejected 403
        const prime = vi.spyOn(axios, 'get').mockResolvedValue({ data: {} });

        await api.post('/login', new URLSearchParams());

        expect(prime).toHaveBeenCalledWith(
            '/api/me',
            expect.objectContaining({ withCredentials: true })
        );
    });

    it('does not fetch one when the cookie is already there', async () => {
        document.cookie = 'XSRF-TOKEN=already-have-one; path=/';
        const prime = vi.spyOn(axios, 'get').mockResolvedValue({ data: {} });

        await api.post('/login', new URLSearchParams());

        expect(prime).not.toHaveBeenCalled();
    });

    it('leaves safe requests alone', async () => {
        const prime = vi.spyOn(axios, 'get').mockResolvedValue({ data: {} });

        await api.get('/api/files');

        expect(prime).not.toHaveBeenCalled();
    });

    it('sends the request anyway when priming fails', async () => {
        vi.spyOn(axios, 'get').mockRejectedValue(new Error('Network Error'));

        await api.post('/login', new URLSearchParams());

        // A dead backend must not swallow the request before it is even attempted
        expect(adapter).toHaveBeenCalled();
    });
});

describe('api session expiry', () => {
    // Answers every request with the given status, failing the way axios's own adapters do.
    const answerWith = (status) => {
        api.defaults.adapter = async (config) => {
            const response = { status, statusText: '', data: '', headers: {}, config };
            throw new AxiosError(`Request failed with status code ${status}`, 'ERR_BAD_REQUEST', config, null, response);
        };
    };

    it('calls the registered handler on a 401 and still rejects', async () => {
        const onUnauthorized = vi.fn();
        setUnauthorizedHandler(onUnauthorized);
        answerWith(401);

        await expect(api.get('/api/files')).rejects.toMatchObject({ response: { status: 401 } });
        expect(onUnauthorized).toHaveBeenCalledOnce();
    });

    it('does not treat a 403 as a lost session', async () => {
        const onUnauthorized = vi.fn();
        setUnauthorizedHandler(onUnauthorized);
        answerWith(403);

        await expect(api.get('/api/files')).rejects.toMatchObject({ response: { status: 403 } });
        expect(onUnauthorized).not.toHaveBeenCalled();
    });
});
