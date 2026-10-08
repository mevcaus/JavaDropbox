import { createSlice, createAsyncThunk } from '@reduxjs/toolkit';
import api from '../services/api';
import { clearUser, logoutUser } from './authSlice';
import { readableError } from '../utils/errors';

// GET lists the tree, POST uploads into a folder (creating it if need be), DELETE removes an item
// (see FileController).
export const FILES_ENDPOINT = '/api/files';
export const FOLDERS_ENDPOINT = '/api/folders';
export const DOWNLOAD_ENDPOINT = '/api/files/download';
export const PREVIEW_ENDPOINT = '/api/files/preview';
// Names and file contents, searched on the server (see SearchController).
export const SEARCH_ENDPOINT = '/api/search';

export const fetchFiles = createAsyncThunk(
    'files/fetchFiles',
    async (_, { rejectWithValue }) => {
        try {
            const response = await api.get(FILES_ENDPOINT);
            return response.data;
        } catch (error) {
            return rejectWithValue(readableError(error, 'Failed to load files.'));
        }
    }
);


export const createDirectory = createAsyncThunk(
    'files/createDirectory',
    async ({ path, name }, { rejectWithValue, dispatch }) => {
        try {
            const formData = new FormData();
            formData.append('path', path);
            formData.append('name', name);
            const response = await api.post(FOLDERS_ENDPOINT, formData);
            dispatch(fetchFiles());
            return response.data;
        } catch (error) {
            return rejectWithValue(readableError(error, 'Failed to create folder.'));
        }
    }
);


// Stores files in the folder at path, which the server creates if it does not exist yet.
const postFiles = async (files, path) => {
    const formData = new FormData();
    formData.append('path', path || '');
    // Named explicitly: for a file from the folder picker, Chrome would otherwise send its
    // webkitRelativePath ("Photos/2024/beach.jpg"), and the server takes only a bare name.
    Array.from(files).forEach((file) => formData.append('files', file, file.name));
    const response = await api.post(FILES_ENDPOINT, formData);
    return response.data;
};

/** Where a file from the folder picker sits inside the chosen folder, e.g. "Photos/2024/beach.jpg". */
export const relativePathOf = (file) => file.webkitRelativePath || file.name;

// The files of a chosen folder grouped by the folder each one goes into below path, parents first.
const filesByFolder = (files, path) => {
    const groups = new Map();
    for (const file of files) {
        const folder = relativePathOf(file).split('/').slice(0, -1).join('/');
        const target = [path, folder].filter(Boolean).join('/');
        if (!groups.has(target)) groups.set(target, []);
        groups.get(target).push(file);
    }
    return [...groups].sort(([a], [b]) => a.localeCompare(b));
};

export const uploadFiles = createAsyncThunk(
    'files/uploadFiles',
    async ({ files, path }, { rejectWithValue, dispatch }) => {
        try {
            return await postFiles(files, path);
        } catch (error) {
            return rejectWithValue(readableError(error, 'Failed to upload files.'));
        } finally {
            // Refresh even after a failure: the files are stored one at a time, so the ones before
            // the failing file are already there.
            dispatch(fetchFiles());
        }
    }
);

/**
 * Uploads a folder chosen with the folder picker into the folder at path, subfolders included.
 * The endpoint stores files in one folder per request, so this sends a request per folder, one
 * after another, and stops at the first that fails. Empty folders are not uploaded: the picker
 * does not report them.
 */
export const uploadFolder = createAsyncThunk(
    'files/uploadFolder',
    async ({ files, path }, { rejectWithValue, dispatch }) => {
        try {
            for (const [folder, batch] of filesByFolder(files, path)) {
                await postFiles(batch, folder);
            }
        } catch (error) {
            return rejectWithValue(readableError(error, 'Failed to upload the folder.'));
        } finally {
            // Once at the end, failure or not: the folders before the failing one are stored.
            dispatch(fetchFiles());
        }
    }
);


export const deleteItem = createAsyncThunk(
    'files/deleteItem',
    async (path, { rejectWithValue, dispatch }) => {
        try {
            await api.delete(FILES_ENDPOINT, { params: { path } });
            return path;
        } catch (error) {
            return rejectWithValue(readableError(error, 'Failed to delete item.'));
        } finally {
            // Refresh even after a failure: the item may already be gone (a 404), or a folder may
            // have been partly removed before the error.
            dispatch(fetchFiles());
        }
    }
);

// The folder being viewed is not kept here: it lives in the URL (/dashboard?path=...), so a reload
// or Back keeps it. Dashboard reads it from there and passes it to selectCurrentFiles.
const initialState = {
    files: [],
    loading: false,
    // Whether the tree has loaded at least once. Refreshes after an upload or delete keep showing
    // the current tree instead of swapping the page for a spinner.
    loaded: false,
    error: null,
    // The request whose answer the store is waiting for. Every mutation fires its own refresh, so
    // answers can arrive out of order, and a reset (logout, expired session) clears this so that a
    // request still in flight cannot put the previous session's tree back.
    latestRequestId: null,
};

const isLatest = (state, action) => action.meta.requestId === state.latestRequestId;

const filesSlice = createSlice({
    name: 'files',
    initialState,
    reducers: {},
    extraReducers: (builder) => {
        builder
            .addCase(fetchFiles.pending, (state, action) => {
                state.latestRequestId = action.meta.requestId;
                state.loading = true;
                state.error = null;
            })
            .addCase(fetchFiles.fulfilled, (state, action) => {
                if (!isLatest(state, action)) return;
                state.latestRequestId = null;
                state.loading = false;
                state.loaded = true;
                state.files = action.payload;
            })
            .addCase(fetchFiles.rejected, (state, action) => {
                if (!isLatest(state, action)) return;
                state.latestRequestId = null;
                state.loading = false;
                state.error = action.payload;
            })
            // Drop the previous session's tree so it is not on screen for whoever signs in next
            .addCase(logoutUser.fulfilled, () => initialState)
            .addCase(clearUser, () => initialState);
    },
});

// One shared empty result: useSelector compares by reference, so a new [] on every call would
// count as a change on every store update.
const NO_FILES = Object.freeze([]);

/**
 * Selector to get the files in a folder of the file tree
 * @param {Object} state - Redux state
 * @param {string} currentPath - The folder, as a path like "docs/2026"; empty for the root
 * @returns {Array} Files in that folder
 */
export const selectCurrentFiles = (state, currentPath) => {
    const { files } = state.files;

    if (!Array.isArray(files)) {
        return NO_FILES;
    }

    if (!currentPath) {
        return files;
    }

    const parts = currentPath.split('/');
    let currentLevel = files;

    for (const part of parts) {
        if (!Array.isArray(currentLevel)) {
            return NO_FILES;
        }

        const folderNode = currentLevel.find(node => node.name === part && node.isDirectory);

        if (folderNode && folderNode.children) {
            currentLevel = folderNode.children;
        } else {
            return NO_FILES;
        }
    }

    return Array.isArray(currentLevel) ? currentLevel : NO_FILES;
};

/**
 * Selector to calculate total size of all files
 * @param {Object} state - Redux state
 * @returns {number} Total size in bytes
 */
export const selectTotalSize = (state) => {
    const { files } = state.files;
    if (!Array.isArray(files)) {
        return 0;
    }
    return files.reduce((acc, file) => acc + file.size, 0);
};

export default filesSlice.reducer;
