import { createSlice, createAsyncThunk } from '@reduxjs/toolkit';
import api from '../services/api';
import { clearUser, logoutUser } from './authSlice';
import { readableError } from '../utils/errors';

// GET lists the tree, POST uploads into a folder, DELETE removes an item (see FileController).
export const FILES_ENDPOINT = '/api/files';
export const FOLDERS_ENDPOINT = '/api/folders';
export const DOWNLOAD_ENDPOINT = '/api/files/download';

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


export const uploadFiles = createAsyncThunk(
    'files/uploadFiles',
    async ({ files, path }, { rejectWithValue, dispatch }) => {
        try {
            const formData = new FormData();
            formData.append('path', path || '');

            // Ensure files is iterable (convert FileList to array if needed)
            const fileArray = Array.from(files);
            fileArray.forEach((file) => formData.append('files', file));

            const response = await api.post(FILES_ENDPOINT, formData);
            dispatch(fetchFiles());
            return response.data;
        } catch (error) {
            return rejectWithValue(readableError(error, 'Failed to upload files.'));
        }
    }
);


export const deleteItem = createAsyncThunk(
    'files/deleteItem',
    async (path, { rejectWithValue, dispatch }) => {
        try {
            await api.delete(FILES_ENDPOINT, { params: { path } });
            dispatch(fetchFiles());
            return path;
        } catch (error) {
            return rejectWithValue(readableError(error, 'Failed to delete item.'));
        }
    }
);

const initialState = {
    files: [],
    currentPath: '',
    loading: false,
    // Whether the tree has loaded at least once. Refreshes after an upload or delete keep showing
    // the current tree instead of swapping the page for a spinner.
    loaded: false,
    error: null,
};

const filesSlice = createSlice({
    name: 'files',
    initialState,
    reducers: {
        setCurrentPath: (state, action) => {
            state.currentPath = action.payload;
        },
    },
    extraReducers: (builder) => {
        builder
            .addCase(fetchFiles.pending, (state) => {
                state.loading = true;
                state.error = null;
            })
            .addCase(fetchFiles.fulfilled, (state, action) => {
                state.loading = false;
                state.loaded = true;
                state.files = action.payload;
            })
            .addCase(fetchFiles.rejected, (state, action) => {
                state.loading = false;
                state.error = action.payload;
            })
            // Drop the previous session's tree so it is not on screen for whoever signs in next
            .addCase(logoutUser.fulfilled, () => initialState)
            .addCase(clearUser, () => initialState);
    },
});


export const { setCurrentPath } = filesSlice.actions;

/**
 * Selector to get files for the current path from the file tree
 * @param {Object} state - Redux state
 * @returns {Array} Files in the current path
 */
export const selectCurrentFiles = (state) => {
    const { files, currentPath } = state.files;

    if (!Array.isArray(files)) {
        return [];
    }

    if (!currentPath) {
        return files;
    }

    const parts = currentPath.split('/');
    let currentLevel = files;

    for (const part of parts) {
        if (!Array.isArray(currentLevel)) {
            return [];
        }

        const folderNode = currentLevel.find(node => node.name === part && node.isDirectory);

        if (folderNode && folderNode.children) {
            currentLevel = folderNode.children;
        } else {
            return [];
        }
    }

    return Array.isArray(currentLevel) ? currentLevel : [];
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
