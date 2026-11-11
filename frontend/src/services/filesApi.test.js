import { beforeEach, describe, expect, it, vi } from 'vitest';
import { makeStore } from '../redux/store';
import { filesApi } from './filesApi';
import { clearUser, loginUser, logoutUser } from '../features/authSlice';
import api from './api';

vi.mock('./api');

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

const tree = (store) => filesApi.endpoints.getFileTree.select()(store.getState()).data;

// The dashboard keeps the tree subscribed while it's open; tags only refetch what's subscribed.
const subscribe = async (store) => {
    const subscription = store.dispatch(filesApi.endpoints.getFileTree.initiate());
    await subscription;
    return subscription;
};

const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

describe('the file tree at the end of a session', () => {
    let store;

    beforeEach(async () => {
        vi.resetAllMocks();
        store = makeStore();
        api.get.mockResolvedValue({ data: [{ name: 'private.txt', isDirectory: false, size: 1 }] });
        await subscribe(store);
    });

    it('is forgotten on logout', async () => {
        api.post.mockResolvedValueOnce({});

        await store.dispatch(logoutUser());

        expect(tree(store)).toBeUndefined();
    });

    it('is kept when the logout fails and the user is still signed in', async () => {
        api.post.mockRejectedValueOnce({ response: { status: 500, data: '' } });

        await store.dispatch(logoutUser());

        expect(tree(store)).toHaveLength(1);
    });

    it('is forgotten when the session expires', () => {
        store.dispatch(clearUser());

        expect(tree(store)).toBeUndefined();
    });
});

// Every change refetches the tree, so listings can arrive out of order.
describe('overlapping listings', () => {
    let store;

    beforeEach(() => {
        vi.resetAllMocks();
        store = makeStore();
    });

    // RTK Query sends one listing at a time; a change that lands while one is in flight gets a
    // fresh listing after it, so the older answer is never the last word.
    it('lists again after a change made while a listing was in flight', async () => {
        const older = deferred();
        api.get.mockReturnValueOnce(older.promise).mockResolvedValueOnce({ data: NEW_TREE });

        store.dispatch(filesApi.endpoints.getFileTree.initiate());
        store.dispatch(filesApi.util.invalidateTags(['Files']));
        older.resolve({ data: OLD_TREE });
        await settle();
        await settle();

        // The item deleted between the two requests must not come back.
        expect(api.get).toHaveBeenCalledTimes(2);
        expect(tree(store)).toEqual(NEW_TREE);
    });

    it.each([
        ['logout', () => logoutUser()],
        ['session expiry', () => clearUser()],
    ])('does not refill the tree from a request that was in flight at %s', async (_label, reset) => {
        store.dispatch(loginUser.fulfilled({ username: 'ada' }, 'seed'));
        const inflight = deferred();
        api.get.mockReturnValueOnce(inflight.promise);
        api.post.mockResolvedValueOnce({});

        store.dispatch(filesApi.endpoints.getFileTree.initiate());
        await store.dispatch(reset());
        inflight.resolve({ data: OLD_TREE });
        await settle();

        expect(tree(store)).toBeUndefined();
    });
});

// A failure doesn't mean nothing changed: an upload stores its files one at a time, and a failed
// delete may be about an item that's already gone.
describe('refreshing after a change', () => {
    let store;

    beforeEach(async () => {
        vi.resetAllMocks();
        api.get.mockResolvedValue({ data: [] });
        store = makeStore();
        await subscribe(store);
    });

    it.each([
        ['succeeds', () => api.post.mockResolvedValueOnce({ data: { message: 'Uploaded 2 files' } })],
        ['fails part-way', () => api.post.mockRejectedValueOnce({ response: { status: 409, data: { message: 'b.txt is a folder' } } })],
    ])('refreshes the tree when an upload %s', async (_label, arrange) => {
        arrange();

        await store.dispatch(
            filesApi.endpoints.uploadFiles.initiate({ body: { files: [new File(['a'], 'a.txt'), new File(['b'], 'b.txt')], path: '' } }),
        );
        await settle();

        expect(api.get).toHaveBeenCalledTimes(2);
    });

    it.each([
        ['succeeds', () => api.delete.mockResolvedValueOnce({ data: { message: 'Deleted folder' } })],
        ['fails', () => api.delete.mockRejectedValueOnce({ response: { status: 404, data: { message: 'Not found' } } })],
    ])('refreshes the tree when a delete %s', async (_label, arrange) => {
        arrange();

        await store.dispatch(filesApi.endpoints.deleteItem.initiate({ path: 'folder' }));
        await settle();

        expect(api.delete).toHaveBeenCalledWith('/api/files', { params: { path: 'folder' } });
        expect(api.get).toHaveBeenCalledTimes(2);
    });
});

// A file with its place inside the uploaded folder, as uploadFolder takes it.
const pickedFile = (relativePath) => ({ file: new File(['x'], relativePath.split('/').at(-1)), relativePath });

const postedFolders = () =>
    api.post.mock.calls.map(([, form]) => [form.get('path'), form.getAll('files').map((file) => file.name)]);

describe('uploadFolder', () => {
    let store;

    beforeEach(async () => {
        vi.resetAllMocks();
        api.get.mockResolvedValue({ data: [] });
        store = makeStore();
        await subscribe(store);
    });

    it('uploads each folder into its place below the open folder, parents first', async () => {
        api.post.mockResolvedValue({ data: { message: 'Uploaded' } });

        const result = await store.dispatch(
            filesApi.endpoints.uploadFolder.initiate({
                files: [pickedFile('Trip/Day 1/a.jpg'), pickedFile('Trip/plan.txt'), pickedFile('Trip/Day 1/b.jpg')],
                path: 'Holidays',
            }),
        );

        expect(result.error).toBeUndefined();
        expect(postedFolders()).toEqual([
            ['Holidays/Trip', ['plan.txt']],
            ['Holidays/Trip/Day 1', ['a.jpg', 'b.jpg']],
        ]);
    });

    it('starts from the root when no folder is open', async () => {
        api.post.mockResolvedValue({ data: { message: 'Uploaded' } });

        await store.dispatch(filesApi.endpoints.uploadFolder.initiate({ files: [pickedFile('Trip/plan.txt')], path: '' }));

        expect(postedFolders()).toEqual([['Trip', ['plan.txt']]]);
    });

    // The browser sends a picked file's webkitRelativePath as its name unless told otherwise, and
    // the server refuses a name with a slash in it.
    it('names each file part by the bare file name', async () => {
        api.post.mockResolvedValue({ data: { message: 'Uploaded' } });
        const append = vi.spyOn(FormData.prototype, 'append');
        const picked = pickedFile('Trip/Day 1/a.jpg');

        await store.dispatch(filesApi.endpoints.uploadFolder.initiate({ files: [picked], path: '' }));

        expect(append).toHaveBeenCalledWith('files', picked.file, 'a.jpg');
        append.mockRestore();
    });

    it('stops at the first folder that fails and refreshes the tree once', async () => {
        api.post
            .mockResolvedValueOnce({ data: { message: 'Uploaded' } })
            .mockRejectedValueOnce({ response: { status: 400, data: { message: '"B" is a file, not a folder' } } });

        const result = await store.dispatch(
            filesApi.endpoints.uploadFolder.initiate({
                files: [pickedFile('Top/a.txt'), pickedFile('Top/B/b.txt'), pickedFile('Top/C/c.txt')],
                path: '',
            }),
        );
        await settle();

        expect(result.error).toEqual({ status: 400, data: { message: '"B" is a file, not a folder' } });
        expect(postedFolders().map(([path]) => path)).toEqual(['Top', 'Top/B']);
        // Once for the subscription, once after the upload.
        expect(api.get).toHaveBeenCalledTimes(2);
    });
});

// The server takes forms and uploads, not JSON, and answers JSON to a form endpoint with a 415.
describe('request bodies', () => {
    let store;

    beforeEach(() => {
        vi.resetAllMocks();
        api.get.mockResolvedValue({ data: [] });
        store = makeStore();
    });

    it('sends a form endpoint its fields as a form', async () => {
        api.post.mockResolvedValue({ data: { url: 'u', expiresAt: '2026-01-01T00:00:00Z' } });

        await store.dispatch(filesApi.endpoints.createShareLink.initiate({ body: { path: 'a.txt', expirationMinutes: 60 } }));

        const [url, body] = api.post.mock.calls[0];
        expect(url).toBe('/api/share');
        expect(body).toBeInstanceOf(URLSearchParams);
        expect(body.toString()).toBe('path=a.txt&expirationMinutes=60');
    });

    it('sends an upload as multipart, one part per file', async () => {
        api.post.mockResolvedValue({ data: { message: 'Uploaded 2 files' } });

        await store.dispatch(
            filesApi.endpoints.uploadFiles.initiate({ body: { files: [new File(['a'], 'a.txt'), new File(['b'], 'b.txt')], path: 'docs' } }),
        );

        const [, body] = api.post.mock.calls[0];
        expect(body).toBeInstanceOf(FormData);
        expect(body.getAll('files').map((file) => file.name)).toEqual(['a.txt', 'b.txt']);
        expect(body.get('path')).toBe('docs');
    });

    it('reports a request that got no answer at all', async () => {
        api.get.mockRejectedValueOnce(new Error('Network Error'));

        const result = await store.dispatch(filesApi.endpoints.getFileTree.initiate());

        expect(result.error).toEqual({ status: 'FETCH_ERROR' });
    });
});
