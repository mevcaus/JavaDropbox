import { useEffect, useState } from 'react';
import api from '../services/api';
import { SEARCH_ENDPOINT } from '../features/filesSlice';
import { readableError } from '../utils/errors';

// How long typing has to pause before the server is asked, so a word typed quickly is one request
// rather than one for every letter.
export const SEARCH_DELAY_MS = 250;

/**
 * Searches names and file contents on the server, everywhere below `folder`, as `query` is typed,
 * and again whenever `files` changes, so that a file deleted or uploaded meanwhile drops out of
 * the results or turns up in them.
 *
 * `answer` is the latest answer for this folder: `{ query, results, total, indexing, error }`,
 * with the results best first. It can be for an earlier query while the current one is on its
 * way, which `pending` says, so the last results stay on screen instead of flashing away between
 * keystrokes. An answer that arrives after a newer search has started is dropped.
 */
export const useFileSearch = (query, folder, files) => {
    const [answer, setAnswer] = useState(null);

    useEffect(() => {
        if (!query) return undefined;
        const controller = new AbortController();
        const timer = setTimeout(async () => {
            try {
                const response = await api.get(SEARCH_ENDPOINT, {
                    params: { q: query, path: folder },
                    signal: controller.signal,
                });
                if (!controller.signal.aborted) {
                    setAnswer({ query, folder, error: null, ...response.data });
                }
            } catch (error) {
                if (!controller.signal.aborted) {
                    setAnswer({
                        query, folder, results: [], total: 0, indexing: false,
                        error: readableError(error, 'Search failed. Please try again.'),
                    });
                }
            }
        }, SEARCH_DELAY_MS);
        return () => {
            clearTimeout(timer);
            controller.abort();
        };
        // files is only a signal that something changed; the server has its own view of them.
    }, [query, folder, files]);

    const forFolder = query && answer?.folder === folder ? answer : null;
    return { answer: forFolder, pending: Boolean(query) && forFolder?.query !== query };
};
