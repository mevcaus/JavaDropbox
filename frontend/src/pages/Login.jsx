import { useState, useEffect } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { useNavigate, useLocation, Link } from 'react-router-dom';
import { loginUser } from '../features/authSlice';
import { Loader2 } from 'lucide-react';
import AnimatedLogo from '../components/AnimatedLogo';
import { useDemoInfo } from '../hooks/useDemoInfo';

const Login = () => {
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const dispatch = useDispatch();
    const navigate = useNavigate();
    const location = useLocation();
    const { isAuthenticated, loading, error, setupRequired } = useSelector((state) => state.auth);
    const demo = useDemoInfo();

    const from = location.state?.from?.pathname || '/dashboard';
    // Set by the page that sent the user here, such as one that has just created their account.
    const notice = location.state?.notice;

    useEffect(() => {
        if (isAuthenticated) {
            navigate(from, { replace: true });
        }
    }, [isAuthenticated, navigate, from]);

    const handleSubmit = (e) => {
        e.preventDefault();
        dispatch(loginUser({ username, password }));
    };

    return (
        <div className="min-h-screen flex items-center justify-center bg-gray-50 py-12 px-4 sm:px-6 lg:px-8">
            <div className="max-w-md w-full space-y-8">
                <div className="text-center">
                    <AnimatedLogo />
                    <h2 className="mt-8 text-3xl font-extrabold text-gray-900">Sign in to your account</h2>
                </div>
                {notice && !error && (
                    <div role="status" className="rounded-md bg-green-50 border border-green-200 p-4 text-sm text-green-900">
                        {notice}
                    </div>
                )}
                {demo && (
                    <div className="rounded-md bg-blue-50 border border-blue-200 p-4 text-sm text-blue-900">
                        <p className="font-medium">This is a public demo, reset every day.</p>
                        <p className="mt-1">
                            Sign in as <code className="font-mono">{demo.username}</code> with the
                            password <code className="font-mono">{demo.password}</code>.
                        </p>
                        <button
                            type="button"
                            onClick={() => {
                                setUsername(demo.username);
                                setPassword(demo.password);
                            }}
                            className="mt-2 font-medium text-blue-700 hover:text-blue-600 underline"
                        >
                            Fill in the demo account
                        </button>
                    </div>
                )}
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
                        <div role="alert" className="text-red-500 text-sm text-center">
                            {typeof error === 'string' ? error : 'Login failed'}
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
                                "Sign in"
                            )}
                        </button>
                    </div>
                    {/* Only while no account exists: afterwards the setup form can only fail. */}
                    {setupRequired && (
                        <div className="text-center">
                            <Link to="/setup" className="font-medium text-blue-600 hover:text-blue-500 text-sm">
                                No account yet? Set up the first user
                            </Link>
                        </div>
                    )}
                </form>
            </div>
        </div>
    );
};

export default Login;
