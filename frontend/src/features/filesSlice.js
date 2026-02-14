import { createSlice, createAsyncThunk } from '@reduxjs/toolkit';
import api from '../services/api';

const API_ENDPOINTS = {
    FILES: '/api/files',
    DIRECTORY_INFO: '/api/directory-info',
    CREATE_DIRECTORY: '/api/create-directory',
    UPLOAD: '/api/upload',
    DELETE: '/api/delete'
};

export const fetchFiles = createAsyncThunk(
    'files/fetchFiles',
    async (_, { rejectWithValue }) => {
        try {
            const response = await api.get(API_ENDPOINTS.FILES);
            return response.data;
        } catch (error) {
            return rejectWithValue(error.response?.data || 'Failed to fetch files');
        }
    }
);


export const fetchDirectoryInfo = createAsyncThunk(
    'files/fetchDirectoryInfo',
    async (_, { rejectWithValue }) => {
        try {
            const response = await api.get(API_ENDPOINTS.DIRECTORY_INFO);
            return response.data;
        } catch (error) {
            return rejectWithValue(error.response?.data || 'Failed to fetch directory info');
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
            const response = await api.post(API_ENDPOINTS.CREATE_DIRECTORY, formData);
            dispatch(fetchFiles());
            return response.data;
        } catch (error) {
            return rejectWithValue(error.response?.data || 'Failed to create directory');
        }
    }
);


export const uploadFiles = createAsyncThunk(
    'files/uploadFiles',
    async ({ files, path }, { rejectWithValue, dispatch }) => {
        try {
            console.log('uploadFiles thunk triggered', { files, path });
            const formData = new FormData();
            formData.append('path', path || '');

            // Ensure files is iterable (convert FileList to array if needed)
            const fileArray = Array.from(files);
            fileArray.forEach((file) => formData.append('files', file));

            const response = await api.post(API_ENDPOINTS.UPLOAD, formData);
            dispatch(fetchFiles());
            return response.data;
        } catch (error) {
            console.error('Upload failed:', error);
            return rejectWithValue(error.response?.data || 'Failed to upload files');
        }
    }
);


export const deleteItem = createAsyncThunk(
    'files/deleteItem',
    async (path, { rejectWithValue, dispatch }) => {
        try {
            await api.delete(`${API_ENDPOINTS.DELETE}?path=${encodeURIComponent(path)}`);
            dispatch(fetchFiles());
            return path;
        } catch (error) {
            return rejectWithValue(error.response?.data || 'Failed to delete item');
        }
    }
);

const filesSlice = createSlice({
    name: 'files',
    initialState: {
        files: [],
        currentPath: '',
        directoryInfo: null,
        loading: false,
        error: null,
    },
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
                state.files = action.payload;
            })
            .addCase(fetchFiles.rejected, (state, action) => {
                state.loading = false;
                state.error = action.payload;
            })
            .addCase(fetchDirectoryInfo.fulfilled, (state, action) => {
                state.directoryInfo = action.payload;
            });
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
