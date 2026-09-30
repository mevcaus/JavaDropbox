import { configureStore } from '@reduxjs/toolkit';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import filesReducer, { deleteItem, fetchFiles, FILES_ENDPOINT, setCurrentPath, uploadFiles } from './filesSlice';
import authReducer, { clearUser, loginUser, logoutUser } from './authSlice';
import api from '../services/api';

vi.mock('../services/api');

const makeStore = () => configureStore({ reducer: { auth: authReducer, files: filesReducer } });

const deferred = () => {
    let resolve;
    let reject;
    const promise = new Promise((res, rej) => {
        resolve = res;
        reject = rej;
    });
    return { promise, resolve, reject };
};

const OLD_TREE = [{ name: 'deleted.txt', isDirectory: false, size: 1 }];
const NEW_TREE = [{ name: 'kept.txt', isDirectory: false, size: 1 }];

describe('filesSlice session reset', () => {
    let store;

    beforeEach(async () => {
        vi.clearAllMocks();
        store = makeStore();
        api.get.mockResolvedValueOnce({ data: [{ name: 'private.txt', isDirectory: false, size: 1 }] });
        await store.dispatch(fetchFiles());
        store.dispatch(setCurrentPath('docs'));
    });

    it('forgets the file tree on logout', async () => {
        api.post.mockResolvedValueOnce({});

        await store.dispatch(logoutUser());

        expect(store.getState().files).toMatchObject({ files: [], currentPath: '' });
    });

    it('keeps the file tree when the logout fails and the user is still signed in', async () => {
        api.post.mockRejectedValueOnce({ response: { status: 500, data: '' } });

        await store.dispatch(logoutUser());

        expect(store.getState().files.files).toHaveLength(1);
    });

    it('forgets the file tree when the session expires', () => {
        store.dispatch(clearUser());

        expect(store.getState().files).toMatchObject({ files: [], currentPath: '' });
    });
});

// Every upload, delete and restore fires its own refresh, so listings can arrive out of order.
describe('filesSlice overlapping fetches', () => {
    let store;

    beforeEach(() => {
        vi.resetAllMocks();
        store = makeStore();
    });

    it('keeps the newest listing when an older request answers last', async () => {
        const older = deferred();
        const newer = deferred();
        api.get.mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise);

        const first = store.dispatch(fetchFiles());
        const second = store.dispatch(fetchFiles());
        newer.resolve({ data: NEW_TREE });
        await second;
        older.resolve({ data: OLD_TREE });
        await first;

        // The item deleted between the two requests must not come back.
        expect(store.getState().files).toMatchObject({ files: NEW_TREE, loading: false });
    });

    it('stays loading until the newest request answers', async () => {
        const older = deferred();
        const newer = deferred();
        api.get.mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise);

        const first = store.dispatch(fetchFiles());
        store.dispatch(fetchFiles());
        older.resolve({ data: OLD_TREE });
        await first;

        expect(store.getState().files).toMatchObject({ files: [], loading: true });
    });

    it('ignores the failure of a request that a newer one replaced', async () => {
        const older = deferred();
        const newer = deferred();
        api.get.mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise);

        const first = store.dispatch(fetchFiles());
        const second = store.dispatch(fetchFiles());
        newer.resolve({ data: NEW_TREE });
        await second;
        older.reject({ response: { status: 500, data: '' } });
        await first;

        expect(store.getState().files).toMatchObject({ files: NEW_TREE, error: null });
    });

    it.each([
        ['logout', () => logoutUser()],
        ['session expiry', () => clearUser()],
    ])('does not refill the tree from a request that was in flight at %s', async (_label, reset) => {
        store.dispatch(loginUser.fulfilled({ username: 'ada' }, 'seed'));
        const inflight = deferred();
        api.get.mockReturnValueOnce(inflight.promise);
        api.post.mockResolvedValueOnce({});

        const pending = store.dispatch(fetchFiles());
        await store.dispatch(reset());
        inflight.resolve({ data: OLD_TREE });
        await pending;

        expect(store.getState().files).toMatchObject({ files: [], loaded: false, loading: false });
    });
});

// A failure does not mean nothing changed: a multi-file upload stores the files one at a time, and
// a failed delete may be about an item that is already gone.
describe('filesSlice refresh after a mutation', () => {
    let store;

    beforeEach(() => {
        vi.resetAllMocks();
        api.get.mockResolvedValue({ data: [] });
        store = makeStore();
    });

    it.each([
        ['succeeds', () => api.post.mockResolvedValueOnce({ data: {} })],
        ['fails part-way', () => api.post.mockRejectedValueOnce({ response: { status: 409, data: { message: 'b.txt is a folder' } } })],
    ])('refreshes the list when an upload %s', async (_label, arrange) => {
        arrange();

        await store.dispatch(uploadFiles({ files: [new File(['a'], 'a.txt'), new File(['b'], 'b.txt')], path: '' }));

        expect(api.get).toHaveBeenCalledWith(FILES_ENDPOINT);
    });

    it.each([
        ['succeeds', () => api.delete.mockResolvedValueOnce({})],
        ['fails', () => api.delete.mockRejectedValueOnce({ response: { status: 404, data: { message: 'Not found' } } })],
    ])('refreshes the list when a delete %s', async (_label, arrange) => {
        arrange();

        await store.dispatch(deleteItem('folder'));

        expect(api.get).toHaveBeenCalledWith(FILES_ENDPOINT);
    });
});
