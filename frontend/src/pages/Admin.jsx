import { useEffect, useState } from 'react';
import { useSelector } from 'react-redux';
import { Navigate } from 'react-router-dom';
import { KeyRound, Loader2, UserPlus } from 'lucide-react';
import api from '../services/api';
import { selectIsAdmin } from '../features/authSlice';
import { useToast } from '../hooks/useToast';
import { readableError } from '../utils/errors';
import { formatDate } from '../utils/date';
import { formatSize } from '../utils/format';
import InviteModal, { INVITES_ENDPOINT } from '../components/InviteModal';
import QuotaModal from '../components/QuotaModal';
import Modal, { ModalActions } from '../components/Modal';
import CopyLinkField from '../components/CopyLinkField';
import { primaryButton } from '../components/modalStyles';

export const USERS_ENDPOINT = '/api/admin/users';

const headerCell = 'px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider';
const cell = 'px-6 py-4 whitespace-nowrap text-sm text-gray-500';
const actionButton =
    'text-xs font-medium px-2 py-1 rounded-md border border-gray-300 text-gray-700 hover:bg-gray-50 disabled:opacity-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500';
const dangerButton =
    'text-xs font-medium px-2 py-1 rounded-md border border-red-200 text-red-700 hover:bg-red-50 disabled:opacity-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-red-500';

const roleLabel = (role) => (role === 'ADMIN' ? 'Admin' : 'User');

const usageLabel = (account) =>
    account.quotaBytes
        ? `${formatSize(account.usedBytes)} of ${formatSize(account.quotaBytes)}`
        : `${formatSize(account.usedBytes)} (no limit)`;

/**
 * Managing the accounts, for admins: who can sign in, as what, with how much space, and the
 * invitations that have not been used yet. Admins never see other accounts' files here, only how
 * much they store.
 */
const Admin = () => {
    const isAdmin = useSelector(selectIsAdmin);
    if (!isAdmin) {
        return <Navigate to="/dashboard" replace />;
    }
    return <AccountsPage />;
};

const AccountsPage = () => {
    const me = useSelector((state) => state.auth.user);
    const [accounts, setAccounts] = useState(null);
    const [invites, setInvites] = useState([]);
    const [error, setError] = useState(null);
    // The account an action is running for, so its row's buttons wait.
    const [busy, setBusy] = useState(null);
    const [inviting, setInviting] = useState(false);
    const [quotaFor, setQuotaFor] = useState(null);
    const [resetLink, setResetLink] = useState(null);
    // Bumped to load the accounts and invitations again after a change.
    const [loads, setLoads] = useState(0);
    const reload = () => setLoads((count) => count + 1);
    const { addToast } = useToast();

    useEffect(() => {
        let cancelled = false;
        Promise.all([api.get(USERS_ENDPOINT), api.get(INVITES_ENDPOINT)])
            .then(([users, open]) => {
                if (cancelled) return;
                setAccounts(users.data);
                setInvites(open.data);
                setError(null);
            })
            .catch((err) => !cancelled && setError(readableError(err, 'Could not load the accounts.')));
        return () => {
            cancelled = true;
        };
    }, [loads]);

    // Runs a change to one account, then shows the accounts as they are now.
    const change = async (account, request, success, failure) => {
        setBusy(account.id);
        try {
            await request();
            addToast(success, 'success');
            reload();
        } catch (err) {
            addToast(readableError(err, failure), 'error');
        } finally {
            setBusy(null);
        }
    };

    const form = (values) => [
        new URLSearchParams(values),
        { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } },
    ];

    const setEnabled = (account, enabled) =>
        change(
            account,
            () => api.put(`${USERS_ENDPOINT}/${account.id}/enabled`, ...form({ enabled })),
            enabled ? `${account.username} can sign in again` : `${account.username} is disabled`,
            'Could not change the account.',
        );

    const setRole = (account, role) =>
        change(
            account,
            () => api.put(`${USERS_ENDPOINT}/${account.id}/role`, ...form({ role })),
            `${account.username} is now ${role === 'ADMIN' ? 'an admin' : 'a user'}`,
            'Could not change the role.',
        );

    const resetPassword = async (account) => {
        setBusy(account.id);
        try {
            const response = await api.post(`${USERS_ENDPOINT}/${account.id}/password-reset`);
            setResetLink({ username: account.username, ...response.data });
        } catch (err) {
            addToast(readableError(err, 'Could not make a reset link.'), 'error');
        } finally {
            setBusy(null);
        }
    };

    const withdraw = async (invite) => {
        try {
            await api.delete(`${INVITES_ENDPOINT}/${invite.id}`);
            addToast(`The invitation for ${invite.username} no longer works`, 'success');
            reload();
        } catch (err) {
            addToast(readableError(err, 'Could not withdraw the invitation.'), 'error');
        }
    };

    if (accounts === null) {
        return error ? (
            <div role="alert" className="text-red-500 text-center py-4">
                {error}
            </div>
        ) : (
            <div className="flex items-center justify-center h-64">
                <Loader2 className="h-8 w-8 animate-spin text-blue-500" aria-label="Loading accounts" />
            </div>
        );
    }

    return (
        <div className="space-y-8">
            <div className="flex flex-wrap gap-3 items-center justify-between">
                <h1 className="text-2xl font-semibold text-gray-900">Users</h1>
                <button
                    type="button"
                    onClick={() => setInviting(true)}
                    className="flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 shadow-sm"
                >
                    <UserPlus className="h-4 w-4 mr-2" aria-hidden="true" />
                    Invite someone
                </button>
            </div>

            {error && (
                <p role="alert" className="text-sm text-red-500">
                    {error}
                </p>
            )}

            <section aria-labelledby="accounts-heading">
                <h2 id="accounts-heading" className="sr-only">
                    Accounts
                </h2>
                <div className="bg-white shadow overflow-x-auto sm:rounded-lg">
                    <table className="min-w-full divide-y divide-gray-200">
                        <thead className="bg-gray-50">
                            <tr>
                                <th scope="col" className={headerCell}>Username</th>
                                <th scope="col" className={headerCell}>Role</th>
                                <th scope="col" className={headerCell}>Status</th>
                                <th scope="col" className={headerCell}>Storage</th>
                                <th scope="col" className="relative px-6 py-3">
                                    <span className="sr-only">Actions</span>
                                </th>
                            </tr>
                        </thead>
                        <tbody className="bg-white divide-y divide-gray-200">
                            {accounts.map((account) => {
                                const isMe = account.username === me;
                                const waiting = busy !== null;
                                return (
                                    <tr key={account.id}>
                                        <td className="px-6 py-4 whitespace-nowrap text-sm font-medium text-gray-900">
                                            {account.username}
                                            {isMe && <span className="ml-2 text-xs font-normal text-gray-500">(you)</span>}
                                        </td>
                                        <td className={cell}>{roleLabel(account.role)}</td>
                                        <td className={cell}>
                                            {account.enabled ? (
                                                <span className="text-green-700">Active</span>
                                            ) : (
                                                <span className="text-red-700">Disabled</span>
                                            )}
                                        </td>
                                        <td className={cell}>{usageLabel(account)}</td>
                                        <td className="px-6 py-4 whitespace-nowrap text-right">
                                            <div className="flex justify-end gap-2">
                                                <button
                                                    type="button"
                                                    disabled={waiting}
                                                    onClick={() => setQuotaFor(account)}
                                                    aria-label={`Change the quota of ${account.username}`}
                                                    className={actionButton}
                                                >
                                                    Quota
                                                </button>
                                                <button
                                                    type="button"
                                                    disabled={waiting}
                                                    onClick={() => resetPassword(account)}
                                                    aria-label={`Make a password reset link for ${account.username}`}
                                                    className={actionButton}
                                                >
                                                    Reset password
                                                </button>
                                                {/* Nobody changes their own role or disables themselves, so nobody
                                                    can lock everyone out; the server refuses it too. */}
                                                {!isMe && (
                                                    <>
                                                        <button
                                                            type="button"
                                                            disabled={waiting}
                                                            onClick={() => setRole(account, account.role === 'ADMIN' ? 'USER' : 'ADMIN')}
                                                            aria-label={
                                                                account.role === 'ADMIN'
                                                                    ? `Make ${account.username} a user`
                                                                    : `Make ${account.username} an admin`
                                                            }
                                                            className={actionButton}
                                                        >
                                                            {account.role === 'ADMIN' ? 'Make user' : 'Make admin'}
                                                        </button>
                                                        <button
                                                            type="button"
                                                            disabled={waiting}
                                                            onClick={() => setEnabled(account, !account.enabled)}
                                                            aria-label={`${account.enabled ? 'Disable' : 'Enable'} ${account.username}`}
                                                            className={account.enabled ? dangerButton : actionButton}
                                                        >
                                                            {account.enabled ? 'Disable' : 'Enable'}
                                                        </button>
                                                    </>
                                                )}
                                            </div>
                                        </td>
                                    </tr>
                                );
                            })}
                        </tbody>
                    </table>
                </div>
            </section>

            <section aria-labelledby="invites-heading">
                <h2 id="invites-heading" className="text-lg font-medium text-gray-900 mb-3">
                    Invitations
                </h2>
                {invites.length === 0 ? (
                    <p className="text-sm text-gray-500">No invitation is waiting to be used.</p>
                ) : (
                    <ul className="divide-y divide-gray-200 bg-white shadow sm:rounded-lg">
                        {invites.map((invite) => (
                            <li key={invite.id} className="px-6 py-3 flex flex-wrap items-center justify-between gap-2">
                                <div className="text-sm">
                                    <div className="font-medium text-gray-900">
                                        {invite.username}{' '}
                                        <span className="font-normal text-gray-500">
                                            as {roleLabel(invite.role).toLowerCase()},{' '}
                                            {invite.quotaBytes ? `${formatSize(invite.quotaBytes)} quota` : 'no quota'}
                                        </span>
                                    </div>
                                    <div className="text-xs text-gray-500">
                                        Expires {formatDate(invite.expiresAt)}
                                        {invite.createdBy && ` · invited by ${invite.createdBy}`}
                                    </div>
                                </div>
                                <button
                                    type="button"
                                    onClick={() => withdraw(invite)}
                                    aria-label={`Withdraw the invitation for ${invite.username}`}
                                    className={dangerButton}
                                >
                                    Withdraw
                                </button>
                            </li>
                        ))}
                    </ul>
                )}
            </section>

            <InviteModal isOpen={inviting} onClose={() => setInviting(false)} onInvited={reload} />

            <QuotaModal
                account={quotaFor}
                onClose={() => setQuotaFor(null)}
                onSaved={() => {
                    addToast(`Changed the quota of ${quotaFor.username}`, 'success');
                    setQuotaFor(null);
                    reload();
                }}
            />

            <Modal
                isOpen={resetLink !== null}
                onClose={() => setResetLink(null)}
                title={
                    <>
                        Password reset for <span className="font-semibold">{resetLink?.username}</span>
                    </>
                }
                icon={<KeyRound className="h-6 w-6 text-blue-600" aria-hidden="true" />}
                iconClassName="bg-blue-100"
            >
                {resetLink && (
                    <div className="mt-4 space-y-3">
                        <CopyLinkField url={resetLink.url} label="Password reset link" />
                        <p className="text-sm text-gray-500">
                            Send this link to {resetLink.username}: it sets a new password once, until{' '}
                            {formatDate(resetLink.expiresAt)}, and signs them out everywhere. Their current password
                            keeps working until then. The link can't be shown again.
                        </p>
                    </div>
                )}
                <ModalActions>
                    <button type="button" className={primaryButton.blue} onClick={() => setResetLink(null)}>
                        Done
                    </button>
                </ModalActions>
            </Modal>
        </div>
    );
};

export default Admin;
