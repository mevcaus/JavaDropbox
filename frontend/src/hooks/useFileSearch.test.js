import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, renderHook, waitFor } from '@testing-library/react';
import { createElement } from 'react';
import { Provider } from 'react-redux';
import { makeStore } from '../redux/store';
import { useFileSearch } from './useFileSearch';
import api from '../services/api';

vi.mock('../services/api');

const answer = (names, extra = {}) => ({
    data: { results: names.map((name) => ({ name, relativePath: name })), total: names.length, indexing: false, ...extra },
});
const searches = () => api.get.mock.calls.filter(([url]) => url === '/api/search');
const shown = (result) => result.current.answer?.results.map((hit) => hit.name);

// The hook with the arguments FileTable gives it, which rerender changes.
const renderSearch = (props) => {
    const store = makeStore();
    return renderHook(({ query, folder, files }) => useFileSearch(query, folder, files), {
        initialProps: { query: '', folder: '', files: [], ...props },
        wrapper: ({ children }) => createElement(Provider, { store }, children),
    });
};

describe('useFileSearch', () => {
    afterEach(() => {
        cleanup();
        vi.resetAllMocks();
    });

    // How the e2e tests wait for the index, and how a person asks again: clear the box and retype.
    it('asks again when the same words are typed after the box was cleared', async () => {
        api.get.mockResolvedValueOnce(answer([], { indexing: true })).mockResolvedValueOnce(answer(['plan.txt']));
        // The same files throughout: a new array would count as a change, which asks again anyway.
        const files = [];
        const { result, rerender } = renderSearch({ query: 'plan', files });
        await waitFor(() => expect(result.current.answer?.indexing).toBe(true));

        rerender({ query: '', folder: '', files });
        rerender({ query: 'plan', folder: '', files });

        await waitFor(() => expect(shown(result)).toEqual(['plan.txt']));
        expect(searches()).toHaveLength(2);
    });

    it('keeps the last answer on screen while the next search is on its way', async () => {
        api.get.mockResolvedValueOnce(answer(['plan.txt'])).mockReturnValueOnce(new Promise(() => {}));
        const { result, rerender } = renderSearch({ query: 'plan' });
        await waitFor(() => expect(shown(result)).toEqual(['plan.txt']));

        rerender({ query: 'plans', folder: '', files: [] });
        await waitFor(() => expect(searches()).toHaveLength(2));

        expect(shown(result)).toEqual(['plan.txt']);
        expect(result.current.pending).toBe(true);
    });

    // The same words in another folder are another search: the first folder's answer isn't shown.
    it('shows an answer only for the folder it was asked about', async () => {
        api.get.mockResolvedValueOnce(answer(['docs/plan.txt'])).mockReturnValueOnce(new Promise(() => {}));
        const { result, rerender } = renderSearch({ query: 'plan', folder: 'docs' });
        await waitFor(() => expect(shown(result)).toEqual(['docs/plan.txt']));

        rerender({ query: 'plan', folder: 'photos', files: [] });

        expect(result.current.answer).toBeNull();
        expect(result.current.pending).toBe(true);
        await waitFor(() => expect(searches()).toHaveLength(2));
        expect(searches()[1][1].params).toEqual({ q: 'plan', path: 'photos' });
    });

    it('shows a failure, and a success after it', async () => {
        api.get
            .mockResolvedValueOnce(answer(['plan.txt']))
            .mockRejectedValueOnce({ response: { status: 500, data: '' } })
            .mockResolvedValueOnce(answer(['plan.txt', 'plan.md']));
        const { result, rerender } = renderSearch({ query: 'plan', files: ['a'] });
        await waitFor(() => expect(shown(result)).toEqual(['plan.txt']));

        rerender({ query: 'plan', folder: '', files: ['b'] });
        await waitFor(() => expect(result.current.answer?.error).toBe('The server is unavailable right now. Please try again.'));

        rerender({ query: 'plan', folder: '', files: ['c'] });
        await waitFor(() => expect(shown(result)).toEqual(['plan.txt', 'plan.md']));
        expect(result.current.answer.error).toBeNull();
    });

    // The files change once per change made in the app (the dashboard lists them again).
    it('asks once each time the files change', async () => {
        api.get.mockResolvedValue(answer(['plan.txt']));
        const { result, rerender } = renderSearch({ query: 'plan', files: ['a'] });
        await waitFor(() => expect(shown(result)).toEqual(['plan.txt']));

        rerender({ query: 'plan', folder: '', files: ['b'] });
        await waitFor(() => expect(searches()).toHaveLength(2));
        await new Promise((resolve) => setTimeout(resolve, 300));

        expect(searches()).toHaveLength(2);
    });
});
