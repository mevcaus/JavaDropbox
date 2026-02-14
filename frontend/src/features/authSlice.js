import { createSlice, createAsyncThunk } from '@reduxjs/toolkit';
import api from '../services/api';

// Async thunk for login
export const loginUser = createAsyncThunk(
    'auth/loginUser',
    async ({ username, password }, { rejectWithValue }) => {
        try {
            // Using URLSearchParams for x-www-form-urlencoded specific standard
            const params = new URLSearchParams();
            params.append('username', username);
            params.append('password', password);

            const response = await api.post('/login', params, {
                headers: {
                    'Content-Type': 'application/x-www-form-urlencoded',
                },
            });
            // The backend redirects to /dashboard on success, which returns 200 OK HTML
            // We assume if 200 OK, we are logged in.
            // Ideally, backend should return JSON. For now, we handle the HTML response or check a specific cookie if possible
            // But since we are moving to standard API later, let's assume successful 200 means OK.
            return { username };
        } catch (error) {
            console.error('Login error details:', error.response);
            // If 401, it throws
            return rejectWithValue(error.response?.data || 'Login failed');
        }
    }
);

export const logoutUser = createAsyncThunk(
    'auth/logoutUser',
    async (_, { rejectWithValue }) => {
        try {
            await api.get('/logout');
        } catch (error) {
            console.error(error);
        }
    }
);

const authSlice = createSlice({
    name: 'auth',
    initialState: {
        user: localStorage.getItem('user') || null,
        isAuthenticated: !!localStorage.getItem('user'),
        loading: false,
        error: null,
    },
    reducers: {
        setUser: (state, action) => {
            state.user = action.payload;
            state.isAuthenticated = true;
            localStorage.setItem('user', action.payload);
        },
        clearUser: (state) => {
            state.user = null;
            state.isAuthenticated = false;
            localStorage.removeItem('user');
        },
    },
    extraReducers: (builder) => {
        builder
            .addCase(loginUser.pending, (state) => {
                state.loading = true;
                state.error = null;
            })
            .addCase(loginUser.fulfilled, (state, action) => {
                state.loading = false;
                state.isAuthenticated = true;
                state.user = action.payload.username;
                localStorage.setItem('user', action.payload.username);
            })
            .addCase(loginUser.rejected, (state, action) => {
                state.loading = false;
                state.error = action.payload || 'Login failed';
            })
            .addCase(logoutUser.fulfilled, (state) => {
                state.user = null;
                state.isAuthenticated = false;
                localStorage.removeItem('user');
            });
    },
});

export const { setUser, clearUser } = authSlice.actions;
export default authSlice.reducer;
