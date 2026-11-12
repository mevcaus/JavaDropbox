import { useDemoInfoQuery } from '../services/apiSlice';

// The demo's account and limits once they have loaded; null before that and on ordinary installs.
export const useDemoInfo = () => useDemoInfoQuery().data ?? null;
