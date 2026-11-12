import { useState } from 'react';
import { skipToken } from '@reduxjs/toolkit/query';
import { useDispatch, useSelector } from 'react-redux';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import {
    useAcceptInviteMutation,
    useDescribeInviteQuery,
    useDescribePasswordResetQuery,
    useResetPasswordMutation,
} from '../services/apiSlice';
import { clearUser, logoutUser } from '../features/authSlice';
import { readableError } from '../utils/errors';
import { formatDate } from '../utils/date';
import { MIN_PASSWORD_LENGTH } from '../utils/passwords';
import logo from '../assets/logo/javadropbox-vertical-color.png';

// What each kind of link is for, and the words on its page.
const PURPOSES = {
    invite: {
        heading: 'Create your account',
        intro: (username) => (
            <>
                You have been invited to JavaDropbox as <span className="font-semibold">{username}</span>.
                Choose a password to finish creating your account.
            </>
        ),
        submit: 'Create account',
        signsOut: 'Creating the account signs you out here, so that you can sign in to it.',
        done: 'Your account is ready. Sign in with your new password.',
        failed: 'Could not create the account.',
    },
    reset: {
        heading: 'Choose a new password',
        intro: (username) => (
            <>
                Choose a new password for <span className="font-semibold">{username}</span>. You will be signed
                out everywhere you are signed in now.
            </>
        ),
        submit: 'Set password',
        signsOut: 'Setting the password signs you out here.',
        done: 'Your password has been changed. Sign in with it.',
        failed: 'Could not change the password.',
    },
};

const inputClass =
    'appearance-none relative block w-full px-3 py-2 border border-gray-300 placeholder-gray-500 text-gray-900 focus:outline-hidden focus:ring-blue-500 focus:border-blue-500 focus:z-10 sm:text-sm';

/**
 * The page an invitation or a password reset link opens: it says which account the link is for and
 * takes the password. A link works once; afterwards, or once it has expired, the page says so.
 * Someone signed in on this browser is signed out once the link is used, so that they can sign in
 * as the new account or with the new password.
 */
const AccountLink = ({ purpose }) => {
    const { token } = useParams();
    const navigate = useNavigate();
    const dispatch = useDispatch();
    const { isAuthenticated, user } = useSelector((state) => state.auth);
    const text = PURPOSES[purpose];
    // Encoded, since the generated endpoints put it into the URL as it is: a token from the address
    // bar could otherwise hold "../" and send the request somewhere else in the API.
    const linkToken = encodeURIComponent(token);
    // Both pages' hooks run, as hooks must, and the one for the other kind of link asks nothing.
    const invite = useDescribeInviteQuery(purpose === 'invite' ? { token: linkToken } : skipToken);
    const reset = useDescribePasswordResetQuery(purpose === 'reset' ? { token: linkToken } : skipToken);
    const described = purpose === 'invite' ? invite : reset;
    const [acceptInvite] = useAcceptInviteMutation();
    const [resetPassword] = useResetPasswordMutation();
    const setPasswordWith = purpose === 'invite' ? acceptInvite : resetPassword;

    // Anything but the link's description, such as the app's HTML from a request that never reached
    // the server, is no link to show a form for.
    const link = typeof described.currentData?.username === 'string' ? described.currentData : null;
    let loadError = null;
    if (described.error) {
        loadError =
            described.error.status === 404
                ? 'This link has expired or has already been used. Ask your admin for a new one.'
                : readableError(described.error, 'Could not open this link.');
    } else if (described.currentData !== undefined && !link) {
        loadError = 'Could not open this link.';
    }
    const [password, setPassword] = useState('');
    const [confirmPassword, setConfirmPassword] = useState('');
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState(null);

    const handleSubmit = async (e) => {
        e.preventDefault();
        setError(null);
        if (password.length < MIN_PASSWORD_LENGTH) {
            setError(`Password must be at least ${MIN_PASSWORD_LENGTH} characters.`);
            return;
        }
        if (password !== confirmPassword) {
            setError('The passwords do not match.');
            return;
        }
        setSaving(true);
        try {
            await setPasswordWith({ token: linkToken, body: { password } }).unwrap();
            if (isAuthenticated) {
                // A reset of this account's own password has already ended its session on the
                // server; anyone else is still signed in there, and stays signed out here regardless.
                const result = await dispatch(logoutUser());
                if (logoutUser.rejected.match(result)) dispatch(clearUser());
            }
            navigate('/login', { replace: true, state: { notice: text.done } });
        } catch (err) {
            setError(readableError(err, text.failed));
            setSaving(false);
        }
    };

    return (
        <div className="min-h-screen flex items-center justify-center bg-gray-50 py-12 px-4 sm:px-6 lg:px-8">
            <div className="max-w-md w-full space-y-8">
                <div className="text-center">
                    <img src={logo} alt="JavaDropbox" className="mx-auto h-36 w-auto" />
                    <h1 className="mt-8 text-3xl font-extrabold text-gray-900">{text.heading}</h1>
                </div>

                {loadError && (
                    <div className="text-center space-y-4">
                        <p role="alert" className="text-sm text-gray-700">
                            {loadError}
                        </p>
                        <Link to="/login" className="font-medium text-blue-600 hover:text-blue-500 text-sm">
                            Go to sign-in
                        </Link>
                    </div>
                )}

                {!link && !loadError && (
                    <div className="flex justify-center">
                        <Loader2 className="h-8 w-8 animate-spin text-blue-500" aria-label="Loading" />
                    </div>
                )}

                {link && (
                    <form className="space-y-6" onSubmit={handleSubmit}>
                        <p className="text-sm text-gray-600 text-center">{text.intro(link.username)}</p>
                        {isAuthenticated && (
                            <p className="rounded-md bg-blue-50 border border-blue-200 p-4 text-sm text-blue-900">
                                You are signed in as <span className="font-semibold">{user}</span>. {text.signsOut}
                            </p>
                        )}
                        {/* For password managers, which save the new password under this name. */}
                        <input type="text" name="username" autoComplete="username" value={link.username} readOnly hidden />
                        <div className="rounded-md shadow-xs -space-y-px">
                            <div>
                                <label htmlFor="new-password" className="sr-only">
                                    Password
                                </label>
                                <input
                                    id="new-password"
                                    type="password"
                                    required
                                    autoComplete="new-password"
                                    minLength={MIN_PASSWORD_LENGTH}
                                    placeholder="Password"
                                    value={password}
                                    onChange={(e) => setPassword(e.target.value)}
                                    className={`${inputClass} rounded-none rounded-t-md`}
                                />
                            </div>
                            <div>
                                <label htmlFor="confirm-new-password" className="sr-only">
                                    Confirm password
                                </label>
                                <input
                                    id="confirm-new-password"
                                    type="password"
                                    required
                                    autoComplete="new-password"
                                    placeholder="Confirm password"
                                    value={confirmPassword}
                                    onChange={(e) => setConfirmPassword(e.target.value)}
                                    className={`${inputClass} rounded-none rounded-b-md`}
                                />
                            </div>
                        </div>
                        <p className="text-xs text-gray-500">
                            At least {MIN_PASSWORD_LENGTH} characters. This link works until{' '}
                            {formatDate(link.expiresAt)}.
                        </p>

                        {error && (
                            <div role="alert" className="text-red-500 text-sm text-center">
                                {error}
                            </div>
                        )}

                        <button
                            type="submit"
                            disabled={saving}
                            className="group relative w-full flex justify-center py-2 px-4 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-hidden focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50"
                        >
                            {saving ? <Loader2 className="animate-spin h-5 w-5 text-white" aria-label="Saving" /> : text.submit}
                        </button>
                    </form>
                )}
            </div>
        </div>
    );
};

export default AccountLink;
