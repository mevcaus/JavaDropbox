import { useId, useRef, useState } from 'react';
import { HardDrive, Loader2 } from 'lucide-react';
import api from '../services/api';
import { readableError } from '../utils/errors';
import { formToQuota, quotaToForm } from '../utils/quota';
import Modal, { ModalActions } from './Modal';
import QuotaFields from './QuotaFields';
import { primaryButton, secondaryButton } from './modalStyles';

/** Changes an account's quota. onSaved runs once the server has stored it. */
const QuotaModal = ({ account, onClose, onSaved }) =>
    account ? <QuotaDialog key={account.id} account={account} onClose={onClose} onSaved={onSaved} /> : null;

const QuotaDialog = ({ account, onClose, onSaved }) => {
    const initial = quotaToForm(account.quotaBytes);
    const [amount, setAmount] = useState(initial.amount);
    const [unit, setUnit] = useState(initial.unit);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState(null);
    const amountRef = useRef(null);
    const quotaId = useId();

    const handleSubmit = async (e) => {
        e.preventDefault();
        const quota = formToQuota(amount, unit);
        if (quota === null) {
            setError('The quota has to be a number more than 0, or empty for no limit.');
            return;
        }
        setSaving(true);
        setError(null);
        try {
            await api.put(`/api/admin/users/${account.id}/quota`, new URLSearchParams({ quota }), {
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            });
            onSaved();
        } catch (err) {
            setError(readableError(err, 'Could not change the quota.'));
        } finally {
            setSaving(false);
        }
    };

    return (
        <Modal
            isOpen
            onClose={() => !saving && onClose()}
            title={
                <>
                    Quota for <span className="font-semibold">{account.username}</span>
                </>
            }
            icon={<HardDrive className="h-6 w-6 text-blue-600" aria-hidden="true" />}
            iconClassName="bg-blue-100"
            initialFocusRef={amountRef}
        >
            <form onSubmit={handleSubmit} className="mt-4 space-y-4">
                <QuotaFields
                    id={quotaId}
                    amount={amount}
                    unit={unit}
                    onAmountChange={setAmount}
                    onUnitChange={setUnit}
                    disabled={saving}
                />
                {error && (
                    <p role="alert" className="text-sm text-red-600">
                        {error}
                    </p>
                )}
                <ModalActions>
                    <button type="submit" className={primaryButton.blue} disabled={saving}>
                        {saving ? <Loader2 className="animate-spin h-5 w-5" aria-label="Saving" /> : 'Save'}
                    </button>
                    <button type="button" className={secondaryButton} onClick={onClose} disabled={saving}>
                        Cancel
                    </button>
                </ModalActions>
            </form>
        </Modal>
    );
};

export default QuotaModal;
