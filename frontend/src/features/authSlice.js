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
            // Nothing is logged here: the error carries the request config, and with it the
            // form body holding the password. The form shows the readable message instead.
            return rejectWithValue(
                readableError(error, 'Login failed', { unauthorized: 'Invalid username or password.' }),
            );
        }
    }
);

export const logoutUser = createAsyncThunk(
    'auth/logoutUser',
    async (_, { rejectWithValue }) => {
        try {
            // Spring Security only accepts POST for logout while CSRF protection is on; a GET is
            // a 404 that leaves the server session alive.
            await api.post('/logout');
        } catch (error) {
            // A 401 means there was no session left to end, which is the outcome logout wants.
            // Anything else (a stale CSRF token, a network error, a 500) may have left the session
            // alive, so the user stays signed in here rather than being shown a logout that did
            // not happen.
            if (error.response?.status !== 401) {
                return rejectWithValue(readableError(error, 'Logout failed.'));
            }
        }
    }
);

// fetchCurrentUser's rejection when the backend is waiting for its first account.
export const SETUP_REQUIRED = 'setup-required';

// Until the first account exists, SetupFilter redirects every request to /setup, and the browser
// follows that redirect without telling the page. The final URL of the request is the only trace.
const wasSentToSetup = (response) => {
    try {
        return new URL(response.request?.responseURL).pathname === '/setup';
    } catch {
        return false;
    }
};

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
                return rejectWithValue(wasSentToSetup(response) ? SETUP_REQUIRED : 'Not authenticated');
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
        // Whether the backend has no account yet, so the sign-in page should point to setup.
        // Learnt from the session check at startup; a 401 there means an account exists.
        setupRequired: false,
    },
    reducers: {
        clearUser: (state) => {
            state.user = null;
            state.isAuthenticated = false;
            localStorage.removeItem('user');
        },
        setupCompleted: (state) => {
            state.setupRequired = false;
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
            .addCase(fetchCurrentUser.rejected, (state, action) => {
                state.isInitialized = true;
                state.setupRequired = action.payload === SETUP_REQUIRED;
                state.isAuthenticated = false;
                state.user = null;
                localStorage.removeItem('user');
            });
    },
});

export const { clearUser, setupCompleted } = authSlice.actions;
export default authSlice.reducer;
