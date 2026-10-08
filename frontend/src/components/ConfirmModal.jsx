import { Loader2 } from 'lucide-react';
import Modal, { ModalActions } from './Modal';
import { primaryButton, secondaryButton } from './modalStyles';

/**
 * Asks before an action that matters, such as disabling an account. While busy the action's request
 * is running: the buttons wait, so a double click cannot send it twice, and the dialog cannot be
 * closed, so its outcome is not hidden.
 */
const ConfirmModal = ({
    isOpen,
    onClose,
    onConfirm,
    title,
    icon,
    iconClassName,
    confirmLabel,
    tone = 'blue',
    busy = false,
    children,
}) => (
    <Modal
        isOpen={isOpen}
        onClose={busy ? () => {} : onClose}
        title={title}
        icon={icon}
        iconClassName={iconClassName}
    >
        <div className="mt-2 space-y-2 text-sm text-gray-500">{children}</div>
        <ModalActions>
            <button type="button" className={primaryButton[tone]} onClick={onConfirm} disabled={busy}>
                {busy ? <Loader2 className="animate-spin h-5 w-5" aria-label="Working" /> : confirmLabel}
            </button>
            <button type="button" className={secondaryButton} onClick={onClose} disabled={busy}>
                Cancel
            </button>
        </ModalActions>
    </Modal>
);

export default ConfirmModal;
