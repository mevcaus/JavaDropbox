import { useEffect, useState } from 'react';
import { skipToken } from '@reduxjs/toolkit/query';
import { useSearchFilesQuery } from '../services/apiSlice';
import { readableError } from '../utils/errors';

// How long typing has to pause before the server is asked, so a word typed quickly is one request
// rather than one for every letter.
export const SEARCH_DELAY_MS = 250;

/**
 * Searches names and file contents on the server, everywhere below `folder`, as `query` is typed,
 * and again after every change to the files made in the app, through the Files cache tag its
 * answers carry, so that a file deleted or uploaded meanwhile drops out of the results or turns up
 * in them. A change made while a search is on its way asks again once that one has answered.
 *
 * `answer` is the latest answer for this folder: `{ query, results, total, indexing, error }`,
 * with the results best first. It can be for an earlier query while the current one is on its
 * way, which `pending` says, so the last results stay on screen instead of flashing away between
 * keystrokes. Each search is a cache entry of its own, so an answer for an older one never shows
 * as the current one's.
 */
export const useFileSearch = (query, folder) => {
    // What to ask, once typing has paused: set from the timer, and cleared with the box, so typing
    // the same words again waits and asks again rather than finding the last search still asked.
    const [asked, setAsked] = useState(null);
    if (!query && asked !== null) setAsked(null);

    useEffect(() => {
        if (!query) return undefined;
        const timer = setTimeout(() => setAsked({ q: query, path: folder }), SEARCH_DELAY_MS);
        return () => clearTimeout(timer);
    }, [query, folder]);

    // A search for what's typed now; one asked for earlier words or another folder is let go.
    const current = query && asked?.q === query && asked.path === folder ? asked : null;
    // Asked again whenever a search starts, even for words searched before: the index may have
    // caught up since, and a search the user starts over is one they want answered now.
    const search = useSearchFilesQuery(current ?? skipToken, { refetchOnMountOrArgChange: true });

    // The last answer shown, kept while the next search is on its way. Updated while rendering, as
    // React recommends for state that follows other state, whenever the current search answers.
    const [shown, setShown] = useState(null);
    const answeredAt = search.isError ? `error:${search.startedTimeStamp}` : search.fulfilledTimeStamp;
    if (current && !search.isFetching && (search.currentData || search.isError)) {
        const key = `${current.q}\u0000${current.path}\u0000${answeredAt}`;
        if (shown?.key !== key) {
            setShown({
                key,
                query: current.q,
                folder: current.path,
                ...(search.isError
                    ? {
                          results: [],
                          total: 0,
                          indexing: false,
                          error: readableError(search.error, 'Search failed. Please try again.'),
                      }
                    : { ...search.currentData, error: null }),
            });
        }
    }

    const forFolder = query && shown?.folder === folder ? shown : null;
    return { answer: forFolder, pending: Boolean(query) && forFolder?.query !== query };
};
