import { createSlice, createAsyncThunk } from '@reduxjs/toolkit';
import api from '../services/api';
import { readableError } from '../utils/errors';

// Async thunk for login
export const loginUser = createAsyncThunk(
    'auth/loginUser',
    async ({ username, password }, { rejectWithValue }) => {
        try {
            // Using URLSearchParams for x-www-form-urlencoded specific standard
            const params = new URLSearchParams();
            params.append('username', username);
            params.append('password', password);

            await api.post('/login', params, {
                headers: {
                    'Content-Type': 'application/x-www-form-urlencoded',
                },
            });
            // The backend answers a successful login with an empty 200.
            return { username };
        } catch (error) {
            console.error('Login error details:', error.response);
            return rejectWithValue(readableError(error, 'Login failed'));
        }
    }
);

export const logoutUser = createAsyncThunk(
    'auth/logoutUser',
    async () => {
        try {
            // Spring Security only accepts POST for logout while CSRF protection is on; a GET is
            // a 404 that leaves the server session alive.
            await api.post('/logout');
        } catch (error) {
            console.error(error);
        }
    }
);

export const fetchCurrentUser = createAsyncThunk(
    'auth/fetchCurrentUser',
    async (_, { rejectWithValue }) => {
        try {
            // Bound the wait: the whole app sits behind a spinner until this settles, and an
            // unreachable backend can otherwise take the better part of a minute to fail (a dead
            // database stalls until the connection pool times out). Failing here just means the
            // user lands on the login screen, so a short timeout is the safe way to give up.
            const response = await api.get('/api/me', { timeout: 8000 });
            // When first-run setup is still pending the backend redirects /api/me to the setup
            // page; the browser follows that redirect and the call resolves as a 200 of HTML.
            // Only a real user payload counts as a session -- a 2xx on its own does not.
            if (typeof response.data?.username !== 'string') {
                return rejectWithValue('Not authenticated');
            }
            return response.data;
        } catch (error) {
            return rejectWithValue(readableError(error, 'Not authenticated'));
        }
    }
);

const authSlice = createSlice({
    name: 'auth',
    initialState: {
        user: localStorage.getItem('user') || null,
        isAuthenticated: !!localStorage.getItem('user'),
        isInitialized: false,
        loading: false,
        error: null,
    },
    reducers: {
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
            })
            .addCase(fetchCurrentUser.fulfilled, (state, action) => {
                state.isInitialized = true;
                state.isAuthenticated = true;
                state.user = action.payload.username;
                localStorage.setItem('user', action.payload.username);
            })
            .addCase(fetchCurrentUser.rejected, (state) => {
                state.isInitialized = true;
                state.isAuthenticated = false;
                state.user = null;
                localStorage.removeItem('user');
            });
    },
});

export const { clearUser } = authSlice.actions;
export default authSlice.reducer;
