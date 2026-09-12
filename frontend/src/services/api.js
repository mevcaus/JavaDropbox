import axios from 'axios';
import { store } from '../redux/store';
import { clearUser } from '../features/authSlice';

const api = axios.create({
    baseURL: '', // Use relative path to leverage Vite proxy
    withCredentials: true, // Important for JSESSIONID cookies
    // These match axios's defaults, but are stated explicitly because they are a contract with
    // Spring Security's CookieCsrfTokenRepository -- axios reads the token Spring writes to the
    // XSRF-TOKEN cookie and echoes it back in this header on every state-changing request.
    xsrfCookieName: 'XSRF-TOKEN',
    xsrfHeaderName: 'X-XSRF-TOKEN',
});

// Spring only hands out the CSRF token on a response, so the first state-changing request a
// browser makes has nothing to send unless some earlier response already set the cookie. The only
// request this app makes before login is the session check, and when that fails -- a backend
// still starting up, a database that is down -- login becomes impossible rather than merely
// failing. Prime the cookie on demand instead of depending on that one call having succeeded.
const CSRF_COOKIE = 'XSRF-TOKEN';
const SAFE_METHODS = ['get', 'head', 'options'];

const hasCsrfToken = () =>
    document.cookie.split('; ').some((cookie) => cookie.startsWith(`${CSRF_COOKIE}=`));

api.interceptors.request.use(async (config) => {
    const method = (config.method ?? 'get').toLowerCase();
    if (SAFE_METHODS.includes(method) || hasCsrfToken()) {
        return config;
    }

    try {
        // Bare axios rather than this instance, so priming cannot recurse through these
        // interceptors. A 401 is the expected answer when signed out and still carries the cookie.
        await axios.get('/api/me', { withCredentials: true, timeout: 8000 });
    } catch {
        // Nothing useful to do here -- if no token arrived the request fails on its own merits.
    }

    return config;
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
