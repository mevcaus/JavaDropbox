import { useState } from 'react';
import { useSelector } from 'react-redux';
import { Navigate } from 'react-router-dom';
import { KeyRound, Loader2, ShieldCheck, UserMinus, UserPlus, UserX } from 'lucide-react';
import {
    useCreatePasswordResetLinkMutation,
    useImportFilesMutation,
    useListAccountsQuery,
    useListInvitesQuery,
    useRevokeInviteMutation,
    useSetAccountEnabledMutation,
    useSetAccountRoleMutation,
} from '../services/apiSlice';
import { selectIsAdmin } from '../features/authSlice';
import { useToast } from '../hooks/useToast';
import { readableError } from '../utils/errors';
import { formatDate } from '../utils/date';
import { formatSize } from '../utils/format';
import InviteModal from '../components/InviteModal';
import QuotaModal from '../components/QuotaModal';
import Modal, { ModalActions } from '../components/Modal';
import ConfirmModal from '../components/ConfirmModal';
import CopyLinkField from '../components/CopyLinkField';
import { primaryButton } from '../components/modalStyles';

const headerCell = 'px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider';
const cell = 'px-6 py-4 whitespace-nowrap text-sm text-gray-500';
const actionButton =
    'text-xs font-medium px-2 py-1 rounded-md border border-gray-300 text-gray-700 hover:bg-gray-50 disabled:opacity-50 focus:outline-hidden focus-visible:ring-2 focus-visible:ring-blue-500';
const dangerButton =
    'text-xs font-medium px-2 py-1 rounded-md border border-red-200 text-red-700 hover:bg-red-50 disabled:opacity-50 focus:outline-hidden focus-visible:ring-2 focus-visible:ring-red-500';

const roleLabel = (role) => (role === 'ADMIN' ? 'Admin' : 'User');

// What the dialog asking before a change to an account says. Enabling an account is not asked
// about: it gives back what disabling took away, and nothing more.
const CONFIRMATIONS = {
    disable: (account) => ({
        title: (
            <>
                Disable <span className="font-semibold">{account.username}</span>?
            </>
        ),
        icon: <UserX className="h-6 w-6 text-red-600" aria-hidden="true" />,
        iconClassName: 'bg-red-100',
        tone: 'red',
        confirmLabel: 'Disable',
        message: (
            <>
                <p>
                    {account.username} is signed out everywhere and can&apos;t sign in, and their share links stop
                    working, until you enable the account again. Their files are kept.
                </p>
                {account.role === 'ADMIN' && (
                    <p>The invitations and password reset links they made stop working for good.</p>
                )}
            </>
        ),
    }),
    makeAdmin: (account) => ({
        title: (
            <>
                Make <span className="font-semibold">{account.username}</span> an admin?
            </>
        ),
        icon: <ShieldCheck className="h-6 w-6 text-blue-600" aria-hidden="true" />,
        iconClassName: 'bg-blue-100',
        tone: 'blue',
        confirmLabel: 'Make admin',
        message: (
            <p>
                Admins manage every account: they invite people, disable accounts, change roles and quotas, and
                make password reset links, which can open any account. {account.username} is signed out, and
                signs in again as an admin.
            </p>
        ),
    }),
    makeUser: (account) => ({
        title: (
            <>
                Make <span className="font-semibold">{account.username}</span> a user?
            </>
        ),
        icon: <UserMinus className="h-6 w-6 text-blue-600" aria-hidden="true" />,
        iconClassName: 'bg-blue-100',
        tone: 'blue',
        confirmLabel: 'Make user',
        message: (
            <p>
                {account.username} no longer manages the accounts. They are signed out, and keep their files
                when they sign in again.
            </p>
        ),
    }),
};

const usageLabel = (account) =>
    account.quotaBytes
        ? `${formatSize(account.usedBytes)} of ${formatSize(account.quotaBytes)}`
        : `${formatSize(account.usedBytes)} (no limit)`;

const count = (n, one, many) => `${n} ${n === 1 ? one : many}`;

// What an import found, in a sentence: counts only, never the files.
const importSummary = (account, { files, folders, missing, skipped }) => {
    const found =
        files + folders === 0
            ? `Nothing new to import for ${account.username}`
            : `Imported ${count(files, 'file', 'files')} and ${count(folders, 'folder', 'folders')} for ${account.username}`;
    const notes = [
        missing > 0 && `${count(missing, 'item is', 'items are')} missing from the store.`,
        skipped > 0 && `${count(skipped, 'item was', 'items were')} left out: names the app doesn't accept.`,
    ].filter(Boolean);
    return notes.length ? `${found}. ${notes.join(' ')}` : found;
};

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
    // Listed again each time the page opens; a change refreshes the list it touched through its tag.
    const listed = useListAccountsQuery(undefined, { refetchOnMountOrArgChange: true });
    const open = useListInvitesQuery(undefined, { refetchOnMountOrArgChange: true });
    const accounts = listed.currentData ?? null;
    const invites = open.currentData ?? [];
    const failure = listed.error ?? open.error;
    const error = failure ? readableError(failure, 'Could not load the accounts.') : null;
    const [setAccountEnabled] = useSetAccountEnabledMutation();
    const [setAccountRole] = useSetAccountRoleMutation();
    const [createPasswordResetLink] = useCreatePasswordResetLinkMutation();
    const [importAccountFiles] = useImportFilesMutation();
    const [revokeInvite] = useRevokeInviteMutation();
    // The account an action is running for. Every row's buttons wait until it is done and the list
    // shows its outcome (they also wait while the list is asked again), so a second click never acts
    // on what the list showed before.
    const [busy, setBusy] = useState(null);
    // Likewise for the invitation being withdrawn.
    const [withdrawing, setWithdrawing] = useState(null);
    const [inviting, setInviting] = useState(false);
    const [quotaFor, setQuotaFor] = useState(null);
    const [resetLink, setResetLink] = useState(null);
    // The change waiting to be confirmed: { action, account }, action a key of CONFIRMATIONS.
    const [confirming, setConfirming] = useState(null);
    const { addToast } = useToast();

    // Runs a change to one account; the list then refreshes through its tag.
    const change = async (account, request, success, failure) => {
        setBusy(account.id);
        try {
            await request().unwrap();
            addToast(success, 'success');
        } catch (err) {
            addToast(readableError(err, failure), 'error');
        } finally {
            setBusy(null);
        }
    };

    const setEnabled = (account, enabled) =>
        change(
            account,
            () => setAccountEnabled({ id: account.id, body: { enabled } }),
            enabled ? `${account.username} can sign in again` : `${account.username} is disabled`,
            'Could not change the account.',
        );

    const setRole = (account, role) =>
        change(
            account,
            () => setAccountRole({ id: account.id, body: { role } }),
            `${account.username} is now ${role === 'ADMIN' ? 'an admin' : 'a user'}`,
            'Could not change the role.',
        );

    const confirmChange = async () => {
        const { action, account } = confirming;
        if (action === 'disable') {
            await setEnabled(account, false);
        } else {
            await setRole(account, action === 'makeAdmin' ? 'ADMIN' : 'USER');
        }
        setConfirming(null);
    };
    const confirmation = confirming && CONFIRMATIONS[confirming.action](confirming.account);

    const resetPassword = async (account) => {
        setBusy(account.id);
        try {
            const link = await createPasswordResetLink({ id: account.id }).unwrap();
            setResetLink({ username: account.username, ...link });
        } catch (err) {
            addToast(readableError(err, 'Could not make a reset link.'), 'error');
        } finally {
            setBusy(null);
        }
    };

    // Gives files copied into the account's folder outside the app rows of their own.
    const importFiles = async (account) => {
        setBusy(account.id);
        try {
            const data = await importAccountFiles({ id: account.id }).unwrap();
            // Anything missing or left out is worth a second look, not a plain success.
            addToast(importSummary(account, data), data.missing > 0 || data.skipped > 0 ? 'info' : 'success');
        } catch (err) {
            addToast(readableError(err, 'Could not import the files.'), 'error');
        } finally {
            setBusy(null);
        }
    };

    const withdraw = async (invite) => {
        setWithdrawing(invite.id);
        try {
            await revokeInvite({ id: invite.id }).unwrap();
            addToast(`The invitation for ${invite.username} no longer works`, 'success');
        } catch (err) {
            addToast(readableError(err, 'Could not withdraw the invitation.'), 'error');
        } finally {
            // The list is asked again either way, through its tag: a failure is most often an
            // invitation used or expired in the meantime, which shouldn't stay listed.
            setWithdrawing(null);
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
                    className="flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-hidden focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 shadow-xs"
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
                <div className="bg-white shadow-sm overflow-x-auto sm:rounded-lg">
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
                                const waiting = busy !== null || listed.isFetching;
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
                                                    aria-label={`Quota for ${account.username}`}
                                                    className={actionButton}
                                                >
                                                    Quota
                                                </button>
                                                <button
                                                    type="button"
                                                    disabled={waiting}
                                                    onClick={() => resetPassword(account)}
                                                    aria-label={`Reset password for ${account.username}`}
                                                    className={actionButton}
                                                >
                                                    Reset password
                                                </button>
                                                <button
                                                    type="button"
                                                    disabled={waiting}
                                                    onClick={() => importFiles(account)}
                                                    aria-label={`Import files for ${account.username}`}
                                                    title="Take in files copied into this account's folder outside the app"
                                                    className={actionButton}
                                                >
                                                    Import files
                                                </button>
                                                {/* Nobody changes their own role or disables themselves, so nobody
                                                    can lock everyone out; the server refuses it too. */}
                                                {!isMe && (
                                                    <>
                                                        <button
                                                            type="button"
                                                            disabled={waiting}
                                                            onClick={() =>
                                                                setConfirming({
                                                                    action: account.role === 'ADMIN' ? 'makeUser' : 'makeAdmin',
                                                                    account,
                                                                })
                                                            }
                                                            aria-label={`${account.role === 'ADMIN' ? 'Make user' : 'Make admin'}, ${account.username}`}
                                                            className={actionButton}
                                                        >
                                                            {account.role === 'ADMIN' ? 'Make user' : 'Make admin'}
                                                        </button>
                                                        <button
                                                            type="button"
                                                            disabled={waiting}
                                                            onClick={() =>
                                                                account.enabled
                                                                    ? setConfirming({ action: 'disable', account })
                                                                    : setEnabled(account, true)
                                                            }
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
                    <ul className="divide-y divide-gray-200 bg-white shadow-sm sm:rounded-lg">
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
                                    disabled={withdrawing !== null || open.isFetching}
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

            <InviteModal isOpen={inviting} onClose={() => setInviting(false)} />

            <QuotaModal
                account={quotaFor}
                onClose={() => setQuotaFor(null)}
                onSaved={() => {
                    addToast(`Changed the quota of ${quotaFor.username}`, 'success');
                    // The list refreshes through its tag.
                    setQuotaFor(null);
                }}
            />

            <ConfirmModal
                isOpen={confirmation !== null}
                onClose={() => setConfirming(null)}
                onConfirm={confirmChange}
                busy={busy !== null}
                title={confirmation?.title}
                icon={confirmation?.icon}
                iconClassName={confirmation?.iconClassName}
                tone={confirmation?.tone}
                confirmLabel={confirmation?.confirmLabel}
            >
                {confirmation?.message}
            </ConfirmModal>

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
                            keeps working until they use it. The link can't be shown again.
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
