import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import api from '../services/api';
import { HardDrive, Loader2 } from 'lucide-react';

const Setup = () => {
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);
    const navigate = useNavigate();

    const handleSubmit = async (e) => {
        e.preventDefault();
        setLoading(true);
        setError(null);

        try {
            // The backend /setup endpoint expects x-www-form-urlencoded params.
            const params = new URLSearchParams();
            params.append('username', username);
            params.append('password', password);

            await api.post('/setup', params, {
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            });

            // Account created — send the user to the login screen.
            navigate('/login', { replace: true });
        } catch (err) {
            const message =
                err.response?.data?.error ||
                err.response?.data?.message ||
                'Setup failed. An account may already exist.';
            setError(message);
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="min-h-screen flex items-center justify-center bg-gray-50 py-12 px-4 sm:px-6 lg:px-8">
            <div className="max-w-md w-full space-y-8">
                <div className="text-center">
                    <div className="mx-auto h-12 w-12 flex items-center justify-center rounded-full bg-blue-100">
                        <HardDrive className="h-8 w-8 text-blue-600" />
                    </div>
                    <h2 className="mt-6 text-3xl font-extrabold text-gray-900">Create your admin account</h2>
                    <p className="mt-2 text-sm text-gray-600">
                        This is a one-time setup for your JavaDropbox instance.
                    </p>
                </div>
                <form className="mt-8 space-y-6" onSubmit={handleSubmit}>
                    <div className="rounded-md shadow-sm -space-y-px">
                        <div>
                            <label htmlFor="username" className="sr-only">Username</label>
                            <input
                                id="username"
                                name="username"
                                type="text"
                                required
                                className="
                                    appearance-none rounded-none rounded-t-md relative block w-full
                                    px-3 py-2 border border-gray-300 placeholder-gray-500 text-gray-900
                                    focus:outline-none focus:ring-blue-500 focus:border-blue-500
                                    focus:z-10 sm:text-sm
                                "
                                placeholder="Username"
                                value={username}
                                onChange={(e) => setUsername(e.target.value)}
                            />
                        </div>
                        <div>
                            <label htmlFor="password" className="sr-only">Password</label>
                            <input
                                id="password"
                                name="password"
                                type="password"
                                required
                                className="
                                    appearance-none rounded-none rounded-b-md relative block w-full
                                    px-3 py-2 border border-gray-300 placeholder-gray-500 text-gray-900
                                    focus:outline-none focus:ring-blue-500 focus:border-blue-500
                                    focus:z-10 sm:text-sm
                                "
                                placeholder="Password"
                                value={password}
                                onChange={(e) => setPassword(e.target.value)}
                            />
                        </div>
                    </div>

                    {error && (
                        <div className="text-red-500 text-sm text-center">
                            {typeof error === 'string' ? error : 'Setup failed'}
                        </div>
                    )}

                    <div>
                        <button
                            type="submit"
                            disabled={loading}
                            className="
                                group relative w-full flex justify-center py-2 px-4
                                border border-transparent text-sm font-medium rounded-md
                                text-white bg-blue-600 hover:bg-blue-700
                                focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500
                                disabled:opacity-50
                            "
                        >
                            {loading ? (
                                <Loader2 className="animate-spin h-5 w-5 text-white" />
                            ) : (
                                "Create account"
                            )}
                        </button>
                    </div>
                    <div className="text-center">
                        <Link to="/login" className="font-medium text-blue-600 hover:text-blue-500 text-sm">
                            Already have an account? Sign in
                        </Link>
                    </div>
                </form>
            </div>
        </div>
    );
};

export default Setup;
