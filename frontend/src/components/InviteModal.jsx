import { useId, useRef, useState } from 'react';
import { Loader2, UserPlus } from 'lucide-react';
import api from '../services/api';
import { readableError } from '../utils/errors';
import { formatDate } from '../utils/date';
import { formToQuota } from '../utils/quota';
import Modal, { ModalActions } from './Modal';
import CopyLinkField from './CopyLinkField';
import QuotaFields from './QuotaFields';
import { primaryButton, secondaryButton } from './modalStyles';

export const INVITES_ENDPOINT = '/api/admin/invites';

/**
 * Invites someone: the admin picks a username, a role and a quota, and gets a one-time link to hand
 * over, at which the invitee chooses a password. onInvited runs once the invitation exists, so the
 * page can list it.
 */
const InviteModal = ({ isOpen, onClose, onInvited }) =>
    isOpen ? <InviteDialog onClose={onClose} onInvited={onInvited} /> : null;

const InviteDialog = ({ onClose, onInvited }) => {
    const [username, setUsername] = useState('');
    const [role, setRole] = useState('USER');
    const [amount, setAmount] = useState('');
    const [unit, setUnit] = useState('GB');
    const [sending, setSending] = useState(false);
    const [error, setError] = useState(null);
    const [link, setLink] = useState(null);
    const usernameRef = useRef(null);
    const quotaId = useId();
    const roleId = useId();

    const handleSubmit = async (e) => {
        e.preventDefault();
        const quota = formToQuota(amount, unit);
        if (quota === null) {
            setError('The quota has to be a number more than 0, or empty for no limit.');
            return;
        }
        setSending(true);
        setError(null);
        try {
            const params = new URLSearchParams({ username: username.trim(), role, quota });
            const response = await api.post(INVITES_ENDPOINT, params, {
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            });
            setLink(response.data);
            onInvited?.();
        } catch (err) {
            setError(readableError(err, 'Could not create the invitation.'));
            usernameRef.current?.focus();
        } finally {
            setSending(false);
        }
    };

    const handleClose = () => {
        if (!sending) onClose();
    };

    return (
        <Modal
            isOpen
            onClose={handleClose}
            title={link ? <>Invitation for <span className="font-semibold">{username.trim()}</span></> : 'Invite someone'}
            icon={<UserPlus className="h-6 w-6 text-blue-600" aria-hidden="true" />}
            iconClassName="bg-blue-100"
            initialFocusRef={usernameRef}
        >
            {link ? (
                <div className="mt-4 space-y-3">
                    <CopyLinkField url={link.url} label="Invitation link" />
                    <p className="text-sm text-gray-500">
                        Send this link to them: whoever opens it chooses the password for the account. It works
                        once, until {formatDate(link.expiresAt)}, and can't be shown again.
                    </p>
                    <ModalActions>
                        <button type="button" className={primaryButton.blue} onClick={onClose}>
                            Done
                        </button>
                    </ModalActions>
                </div>
            ) : (
                <form onSubmit={handleSubmit} className="mt-4 space-y-4">
                    <div>
                        <label htmlFor="invite-username" className="block text-sm font-medium text-gray-700 mb-1">
                            Username
                        </label>
                        <input
                            id="invite-username"
                            ref={usernameRef}
                            type="text"
                            required
                            maxLength={255}
                            autoComplete="off"
                            value={username}
                            readOnly={sending}
                            onChange={(e) => setUsername(e.target.value)}
                            className="block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 sm:text-sm py-2 px-3 border"
                        />
                    </div>
                    <div>
                        <label htmlFor={roleId} className="block text-sm font-medium text-gray-700 mb-1">
                            Role
                        </label>
                        <select
                            id={roleId}
                            value={role}
                            disabled={sending}
                            onChange={(e) => setRole(e.target.value)}
                            className="block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 sm:text-sm py-2 px-3 border"
                        >
                            <option value="USER">User: has files of their own</option>
                            <option value="ADMIN">Admin: also manages the accounts</option>
                        </select>
                    </div>
                    <QuotaFields
                        id={quotaId}
                        amount={amount}
                        unit={unit}
                        onAmountChange={setAmount}
                        onUnitChange={setUnit}
                        disabled={sending}
                    />
                    {error && (
                        <p role="alert" className="text-sm text-red-600">
                            {error}
                        </p>
                    )}
                    <ModalActions>
                        <button type="submit" className={primaryButton.blue} disabled={sending}>
                            {sending ? <Loader2 className="animate-spin h-5 w-5" aria-label="Creating" /> : 'Create invitation'}
                        </button>
                        <button type="button" className={secondaryButton} onClick={handleClose} disabled={sending}>
                            Cancel
                        </button>
                    </ModalActions>
                </form>
            )}
        </Modal>
    );
};

export default InviteModal;
