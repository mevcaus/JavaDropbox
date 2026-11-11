import { configureStore, createListenerMiddleware, isAnyOf } from '@reduxjs/toolkit';
import authReducer, { clearUser, loginUser, logoutUser } from '../features/authSlice';
// apiSlice rather than baseApi: importing it is what adds the cache tags and the extra endpoints to
// the one API object they share.
import { apiSlice } from '../services/apiSlice';

/**
 * A store with the API slice wired in. The app makes one; tests make their own, so nothing cached
 * carries over from one test to the next.
 */
export const makeStore = () => {
    // Drop everything cached for the previous session when it ends, and again when someone signs in,
    // so whoever signs in next never sees the last account's files, even for a moment. An answer
    // still in flight for the old session is dropped too.
    const listener = createListenerMiddleware();
    listener.startListening({
        matcher: isAnyOf(logoutUser.fulfilled, clearUser, loginUser.fulfilled),
        effect: (_action, { dispatch }) => {
            dispatch(apiSlice.util.resetApiState());
        },
    });

    return configureStore({
        reducer: {
            auth: authReducer,
            [apiSlice.reducerPath]: apiSlice.reducer,
        },
        middleware: (getDefault) => getDefault().prepend(listener.middleware).concat(apiSlice.middleware),
    });
};

export const store = makeStore();
