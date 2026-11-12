import { useGetDemoInfoQuery } from '../services/apiSlice';

// The demo's account and limits once they have loaded; null before that and on ordinary installs,
// which answer the request with a 404.
export const useDemoInfo = () => {
    const { data } = useGetDemoInfoQuery();
    return typeof data?.username === 'string' ? data : null;
};
