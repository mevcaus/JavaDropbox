import axios from 'axios';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import api from './api';

const clearCookies = () => {
    document.cookie.split('; ').forEach((cookie) => {
        const name = cookie.split('=')[0];
        if (name) {
            document.cookie = `${name}=; Max-Age=0; path=/`;
        }
    });
};

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
