import api from './api';

let request;

// The public demo's account and limits (GET /api/demo), or null on an ordinary install, where the
// endpoint does not exist. Asked once per page load: nothing in it changes while the page is open
// except the next reset time, which is only shown, never relied on.
export const getDemoInfo = () => {
    request ??= Promise.resolve()
        .then(() => api.get('/api/demo'))
        .then((response) => (typeof response?.data?.username === 'string' ? response.data : null))
        .catch(() => null);
    return request;
};

// For tests, which each start from a page that has not asked yet.
export const resetDemoInfo = () => {
    request = undefined;
};
