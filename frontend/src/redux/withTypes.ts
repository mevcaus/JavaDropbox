import { createAsyncThunk } from '@reduxjs/toolkit';
import { useDispatch, useSelector } from 'react-redux';
import type { AppDispatch, RootState } from './store';

// Typed versions of what react-redux and Redux Toolkit export, for TypeScript code to use instead,
// so the store's state and dispatch, and a thunk's rejection message, are known without spelling
// them out each time.

// Marked pure so a bundle that doesn't use them leaves them out.
export const useAppDispatch = /* @__PURE__ */ useDispatch.withTypes<AppDispatch>();
export const useAppSelector = /* @__PURE__ */ useSelector.withTypes<RootState>();

/**
 * A thunk that rejects with a readable message, for the page to show. It's typed with the whole
 * app's state, as Redux Toolkit recommends, so a thunk that reads state or dispatches gets both
 * typed; the cost is that it's dispatched on the app's store (`makeStore()`), not a slice's own.
 */
export const createAppAsyncThunk = createAsyncThunk.withTypes<{
    state: RootState;
    dispatch: AppDispatch;
    rejectValue: string;
}>();
