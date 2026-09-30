import { configureStore } from '@reduxjs/toolkit';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import filesReducer, { fetchFiles, setCurrentPath } from './filesSlice';
import authReducer, { clearUser, logoutUser } from './authSlice';
import api from '../services/api';

vi.mock('../services/api');

const makeStore = () => configureStore({ reducer: { auth: authReducer, files: filesReducer } });

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
