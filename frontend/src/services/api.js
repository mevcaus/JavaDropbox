import axios from 'axios';
import { store } from '../redux/store';
import { clearUser } from '../features/authSlice';

const api = axios.create({
    baseURL: '', // Use relative path to leverage Vite proxy
    withCredentials: true, // Important for JSESSIONID cookies
});

// Add response interceptor to handle 401/403 errors (e.g. redirect to login)
api.interceptors.response.use(
    (response) => response,
    (error) => {
        if (error.response && (error.response.status === 401 || error.response.status === 403)) {
            // Dispatch logout action or redirect to login
            store.dispatch(clearUser());
            console.error('Unauthorized access', error);
        }
        return Promise.reject(error);
    }
);

export default api;
