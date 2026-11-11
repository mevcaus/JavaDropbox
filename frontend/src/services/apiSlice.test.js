import { beforeEach, describe, expect, it, vi } from 'vitest';
import { makeStore } from '../redux/store';
import { apiSlice } from './apiSlice';
import { clearUser, loginUser, logoutUser } from '../features/authSlice';
import { AxiosError } from 'axios';
import api from './api';
import { baseQuery } from './baseQuery';

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

const tree = (store) => apiSlice.endpoints.getFileTree.select()(store.getState()).data;

// The dashboard keeps the tree subscribed while it's open; tags only refetch what's subscribed.
const subscribe = async (store) => {
    const subscription = store.dispatch(apiSlice.endpoints.getFileTree.initiate());
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

    // Whatever an earlier session left behind is gone before the new one's screens load.
    it('is forgotten when someone signs in', () => {
        store.dispatch(loginUser.fulfilled({ username: 'grace', role: 'USER' }, 'signin'));

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

        store.dispatch(apiSlice.endpoints.getFileTree.initiate());
        store.dispatch(apiSlice.util.invalidateTags(['Files']));
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

        store.dispatch(apiSlice.endpoints.getFileTree.initiate());
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
            apiSlice.endpoints.uploadFiles.initiate({ body: { files: [new File(['a'], 'a.txt'), new File(['b'], 'b.txt')], path: '' } }),
        );
        await settle();

        expect(api.get).toHaveBeenCalledTimes(2);
    });

    it.each([
        ['succeeds', () => api.delete.mockResolvedValueOnce({ data: { message: 'Deleted folder' } })],
        ['fails', () => api.delete.mockRejectedValueOnce({ response: { status: 404, data: { message: 'Not found' } } })],
    ])('refreshes the tree when a delete %s', async (_label, arrange) => {
        arrange();

        await store.dispatch(apiSlice.endpoints.deleteItem.initiate({ path: 'folder' }));
        await settle();

        expect(api.delete).toHaveBeenCalledWith('/api/files', expect.objectContaining({ params: { path: 'folder' } }));
        expect(api.get).toHaveBeenCalledTimes(2);
    });
});

// Which lists a change asks again, by the tags it invalidates.
describe('refreshing versions and share links', () => {
    let store;
    const gets = (url) => api.get.mock.calls.filter(([called]) => called === url).length;

    beforeEach(() => {
        vi.resetAllMocks();
        api.get.mockResolvedValue({ data: [] });
        store = makeStore();
    });

    it('refreshes the tree and that file\'s versions after a restore, not another file\'s', async () => {
        await subscribe(store);
        await store.dispatch(apiSlice.endpoints.listFileVersions.initiate({ fileId: 7 }));
        await store.dispatch(apiSlice.endpoints.listFileVersions.initiate({ fileId: 8 }));
        api.post.mockResolvedValueOnce({ data: { message: 'Version 1 restored' } });

        await store.dispatch(apiSlice.endpoints.restoreVersion.initiate({ fileId: 7, version: 1, mode: 'OVERWRITE' }));
        await settle();

        expect(gets('/api/files')).toBe(2);
        expect(gets('/api/files/7/versions')).toBe(2);
        expect(gets('/api/files/8/versions')).toBe(1);
    });

    it('refreshes the shared item\'s links after a new link, not another item\'s', async () => {
        await store.dispatch(apiSlice.endpoints.listShareLinks.initiate({ path: 'a.txt' }));
        await store.dispatch(apiSlice.endpoints.listShareLinks.initiate({ path: 'b.txt' }));
        api.post.mockResolvedValueOnce({ data: { url: 'u', expiresAt: '2026-01-01T00:00:00Z' } });

        await store.dispatch(apiSlice.endpoints.createShareLink.initiate({ body: { path: 'a.txt', expirationMinutes: 60 } }));
        await settle();

        const paths = api.get.mock.calls.filter(([url]) => url === '/api/share').map(([, config]) => config.params.path);
        expect(paths).toEqual(['a.txt', 'b.txt', 'a.txt']);
    });

    // Uploading over a file keeps its old content as a version; a delete takes versions and links.
    it('refreshes the versions after an upload, and the versions and links after a delete', async () => {
        await store.dispatch(apiSlice.endpoints.listFileVersions.initiate({ fileId: 7 }));
        await store.dispatch(apiSlice.endpoints.listShareLinks.initiate({ path: 'a.txt' }));
        api.post.mockResolvedValueOnce({ data: { message: 'Uploaded 1 file' } });
        api.delete.mockResolvedValueOnce({ data: { message: 'Deleted a.txt' } });

        await store.dispatch(apiSlice.endpoints.uploadFiles.initiate({ body: { files: [new File(['a'], 'a.txt')], path: '' } }));
        await settle();
        expect([gets('/api/files/7/versions'), gets('/api/share')]).toEqual([2, 1]);

        await store.dispatch(apiSlice.endpoints.deleteItem.initiate({ path: 'a.txt' }));
        await settle();
        expect([gets('/api/files/7/versions'), gets('/api/share')]).toEqual([3, 2]);
    });

    // A link's id doesn't say whose item it shares.
    it('refreshes every list of links after a revoke', async () => {
        await store.dispatch(apiSlice.endpoints.listShareLinks.initiate({ path: 'a.txt' }));
        await store.dispatch(apiSlice.endpoints.listShareLinks.initiate({ path: 'b.txt' }));
        api.delete.mockResolvedValueOnce({ data: { message: 'Share link revoked' } });

        await store.dispatch(apiSlice.endpoints.revokeShareLink.initiate({ id: 3 }));
        await settle();

        expect(gets('/api/share')).toBe(4);
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
            apiSlice.endpoints.uploadFolder.initiate({
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

        await store.dispatch(apiSlice.endpoints.uploadFolder.initiate({ files: [pickedFile('Trip/plan.txt')], path: '' }));

        expect(postedFolders()).toEqual([['Trip', ['plan.txt']]]);
    });

    // The browser sends a picked file's webkitRelativePath as its name unless told otherwise, and
    // the server refuses a name with a slash in it.
    it('names each file part by the bare file name', async () => {
        api.post.mockResolvedValue({ data: { message: 'Uploaded' } });
        const append = vi.spyOn(FormData.prototype, 'append');
        const picked = pickedFile('Trip/Day 1/a.jpg');

        await store.dispatch(apiSlice.endpoints.uploadFolder.initiate({ files: [picked], path: '' }));

        expect(append).toHaveBeenCalledWith('files', picked.file, 'a.jpg');
        append.mockRestore();
    });

    it('stops at the first folder that fails and refreshes the tree once', async () => {
        api.post
            .mockResolvedValueOnce({ data: { message: 'Uploaded' } })
            .mockRejectedValueOnce({ response: { status: 400, data: { message: '"B" is a file, not a folder' } } });

        const result = await store.dispatch(
            apiSlice.endpoints.uploadFolder.initiate({
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

        await store.dispatch(apiSlice.endpoints.createShareLink.initiate({ body: { path: 'a.txt', expirationMinutes: 60 } }));

        const [url, body] = api.post.mock.calls[0];
        expect(url).toBe('/api/share');
        expect(body).toBeInstanceOf(URLSearchParams);
        expect(body.toString()).toBe('path=a.txt&expirationMinutes=60');
    });

    it('sends an upload as multipart, one part per file', async () => {
        api.post.mockResolvedValue({ data: { message: 'Uploaded 2 files' } });

        await store.dispatch(
            apiSlice.endpoints.uploadFiles.initiate({ body: { files: [new File(['a'], 'a.txt'), new File(['b'], 'b.txt')], path: 'docs' } }),
        );

        const [, body] = api.post.mock.calls[0];
        expect(body).toBeInstanceOf(FormData);
        expect(body.getAll('files').map((file) => file.name)).toEqual(['a.txt', 'b.txt']);
        expect(body.get('path')).toBe('docs');
    });

    it('reports a request that got no answer at all', async () => {
        api.get.mockRejectedValueOnce(new AxiosError('Network Error', AxiosError.ERR_NETWORK));

        const result = await store.dispatch(apiSlice.endpoints.getFileTree.initiate());

        expect(result.error).toEqual({ status: 'FETCH_ERROR' });
    });

    it('sends a PUT as a form', async () => {
        api.put.mockResolvedValue({ data: { message: 'Quota changed' } });

        await store.dispatch(apiSlice.endpoints.setAccountQuota.initiate({ id: 3, body: { quota: '5GB' } }));

        const [url, body] = api.put.mock.calls[0];
        expect(url).toBe('/api/admin/users/3/quota');
        expect(body.toString()).toBe('quota=5GB');
    });

    it('sends a new folder as multipart, as the app always has', async () => {
        api.post.mockResolvedValue({ data: { message: 'Created folder Trip' } });

        await store.dispatch(apiSlice.endpoints.createFolder.initiate({ body: { path: 'Holidays', name: 'Trip' } }));

        const [url, body] = api.post.mock.calls[0];
        expect(url).toBe('/api/folders');
        expect(body).toBeInstanceOf(FormData);
        expect([body.get('path'), body.get('name')]).toEqual(['Holidays', 'Trip']);
    });

    it('sends a part that is a Blob rather than a File', async () => {
        api.post.mockResolvedValue({ data: { message: 'Uploaded 1 file' } });

        await store.dispatch(apiSlice.endpoints.uploadFiles.initiate({ body: { files: [new Blob(['x'])], path: '' } }));

        const [, body] = api.post.mock.calls[0];
        expect(body.getAll('files')).toHaveLength(1);
    });
});

// Mistakes in the slice, not failures of a request, so they aren't reported as one.
describe('baseQuery', () => {
    const context = { endpoint: 'test', signal: new AbortController().signal };

    beforeEach(() => vi.resetAllMocks());

    it('refuses a method the API takes nowhere, rather than sending it as another', async () => {
        await expect(baseQuery({ url: '/api/files', method: 'PATCH' }, context)).rejects.toThrow('test uses PATCH');
        expect(api.post).not.toHaveBeenCalled();
    });

    it('lets an error that is not about the request through', async () => {
        api.get.mockRejectedValueOnce(new TypeError('a bug in an interceptor'));

        await expect(baseQuery({ url: '/api/files' }, context)).rejects.toThrow(TypeError);
    });

    it('passes the request\'s abort signal on, so a superseded request can be cancelled', async () => {
        api.get.mockResolvedValueOnce({ data: [] });

        await baseQuery({ url: '/api/files' }, context);

        expect(api.get).toHaveBeenCalledWith('/api/files', expect.objectContaining({ signal: context.signal }));
    });
});
