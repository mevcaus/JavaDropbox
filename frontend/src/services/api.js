import axios from 'axios';
import { store } from '../redux/store';
import { clearUser } from '../features/authSlice';

const api = axios.create({
    baseURL: '', // Use relative path to leverage Vite proxy
    withCredentials: true, // Important for JSESSIONID cookies
});

// Add response interceptor to drop the cached session when the backend says it is gone.
// Only 401 means "no session" -- a 403 is an authenticated user being refused a specific action,
// and signing them out over it would throw away a session that is still valid.
api.interceptors.response.use(
    (response) => response,
    (error) => {
        if (error.response && error.response.status === 401) {
            // Dispatch logout action or redirect to login
            store.dispatch(clearUser());
            console.error('Unauthorized access', error);
        }
        return Promise.reject(error);
    }
);

export default api;
