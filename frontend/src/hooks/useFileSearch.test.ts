import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, renderHook, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { Provider } from 'react-redux';
import { makeStore } from '../redux/store';
import { SEARCH_DELAY_MS, useFileSearch, type SearchAnswer } from './useFileSearch';
import { apiSlice } from '../services/apiSlice';
import mockedApi from '../services/api';

vi.mock('../services/api');
const api = vi.mocked(mockedApi);

const answer = (names: string[], extra = {}) => ({
    data: { results: names.map((name) => ({ name, relativePath: name })), total: names.length, indexing: false, ...extra },
});
const searches = () => api.get.mock.calls.filter(([url]) => url === '/api/search');
const shown = (result: { current: { answer: SearchAnswer | null } }) => result.current.answer?.results.map((hit) => hit.name);

// The hook with the arguments FileTable gives it, which rerender changes; changeFiles does what
// every change to the files made in the app does.
const renderSearch = (props: { query?: string; folder?: string }) => {
    const store = makeStore();
    const hook = renderHook(({ query, folder }) => useFileSearch(query, folder), {
        initialProps: { query: '', folder: '', ...props },
        wrapper: ({ children }: { children: ReactNode }) => createElement(Provider, { store, children }),
    });
    return { ...hook, changeFiles: () => store.dispatch(apiSlice.util.invalidateTags(['Files'])) };
};

describe('useFileSearch', () => {
    afterEach(() => {
        cleanup();
        vi.resetAllMocks();
    });

    // How the e2e tests wait for the index, and how a person asks again: clear the box and retype.
    it('asks again when the same words are typed after the box was cleared', async () => {
        api.get.mockResolvedValueOnce(answer([], { indexing: true })).mockResolvedValueOnce(answer(['plan.txt']));
        const { result, rerender } = renderSearch({ query: 'plan' });
        await waitFor(() => expect(result.current.answer?.indexing).toBe(true));

        rerender({ query: '', folder: '' });
        rerender({ query: 'plan', folder: '' });

        await waitFor(() => expect(shown(result)).toEqual(['plan.txt']));
        expect(searches()).toHaveLength(2);
    });

    // Retyped words are typing like any other: asked once the typing pauses, not at the first key.
    it('waits for typing to pause before asking for words searched before', async () => {
        api.get.mockResolvedValue(answer(['plan.txt']));
        const { result, rerender } = renderSearch({ query: 'plan' });
        await waitFor(() => expect(shown(result)).toEqual(['plan.txt']));

        rerender({ query: '', folder: '' });
        rerender({ query: 'plan', folder: '' });
        await new Promise((resolve) => setTimeout(resolve, SEARCH_DELAY_MS / 2));

        expect(searches()).toHaveLength(1);
        await waitFor(() => expect(searches()).toHaveLength(2));
    });

    it('keeps the last answer on screen while the next search is on its way', async () => {
        api.get.mockResolvedValueOnce(answer(['plan.txt'])).mockReturnValueOnce(new Promise(() => {}));
        const { result, rerender } = renderSearch({ query: 'plan' });
        await waitFor(() => expect(shown(result)).toEqual(['plan.txt']));

        rerender({ query: 'plans', folder: '' });
        await waitFor(() => expect(searches()).toHaveLength(2));

        expect(shown(result)).toEqual(['plan.txt']);
        expect(result.current.pending).toBe(true);
    });

    // The same words in another folder are another search: the first folder's answer isn't shown.
    it('shows an answer only for the folder it was asked about', async () => {
        api.get.mockResolvedValueOnce(answer(['docs/plan.txt'])).mockReturnValueOnce(new Promise(() => {}));
        const { result, rerender } = renderSearch({ query: 'plan', folder: 'docs' });
        await waitFor(() => expect(shown(result)).toEqual(['docs/plan.txt']));

        rerender({ query: 'plan', folder: 'photos' });

        expect(result.current.answer).toBeNull();
        expect(result.current.pending).toBe(true);
        await waitFor(() => expect(searches()).toHaveLength(2));
        expect(searches()[1][1]?.params).toEqual({ q: 'plan', path: 'photos' });
    });

    it('shows a failure, and a success after it', async () => {
        api.get
            .mockResolvedValueOnce(answer(['plan.txt']))
            .mockRejectedValueOnce({ response: { status: 500, data: '' } })
            .mockResolvedValueOnce(answer(['plan.txt', 'plan.md']));
        const { result, changeFiles } = renderSearch({ query: 'plan' });
        await waitFor(() => expect(shown(result)).toEqual(['plan.txt']));

        changeFiles();
        await waitFor(() => expect(result.current.answer?.error).toBe('The server is unavailable right now. Please try again.'));

        changeFiles();
        await waitFor(() => expect(shown(result)).toEqual(['plan.txt', 'plan.md']));
        expect(result.current.answer?.error).toBeNull();
    });

    it('asks once each time the files change', async () => {
        api.get.mockResolvedValue(answer(['plan.txt']));
        const { result, changeFiles } = renderSearch({ query: 'plan' });
        await waitFor(() => expect(shown(result)).toEqual(['plan.txt']));

        changeFiles();
        await waitFor(() => expect(searches()).toHaveLength(2));
        await new Promise((resolve) => setTimeout(resolve, 300));

        expect(searches()).toHaveLength(2);
    });

    // The answer on its way may be from before the change, so it's asked again once it arrives.
    it('asks again after a change made while a search was on its way', async () => {
        let answerFirst!: (value: unknown) => void;
        api.get
            .mockReturnValueOnce(new Promise((resolve) => (answerFirst = resolve)))
            .mockResolvedValueOnce(answer(['new.txt']));
        const { result, changeFiles } = renderSearch({ query: 'plan' });
        await waitFor(() => expect(searches()).toHaveLength(1));

        changeFiles();
        answerFirst(answer(['old.txt']));

        await waitFor(() => expect(shown(result)).toEqual(['new.txt']));
        expect(searches()).toHaveLength(2);
    });
});
