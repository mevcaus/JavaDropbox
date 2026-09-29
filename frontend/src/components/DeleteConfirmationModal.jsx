import { AlertTriangle, Loader2 } from 'lucide-react';
import Modal, { ModalActions } from './Modal';
import { primaryButton, secondaryButton } from './modalStyles';

const DeleteConfirmationModal = ({ isOpen, onClose, onConfirm, itemName, isDeleting = false }) => (
    <Modal
        isOpen={isOpen}
        // Closing mid-request would hide the outcome; the request finishes either way.
        onClose={isDeleting ? () => {} : onClose}
        title="Delete Item"
        icon={<AlertTriangle className="h-6 w-6 text-red-600" aria-hidden="true" />}
        iconClassName="bg-red-100"
    >
        <div className="mt-2">
            <p className="text-sm text-gray-500">
                Are you sure you want to delete <span className="font-semibold break-all">{itemName}</span>? This action
                cannot be undone.
            </p>
        </div>
        <ModalActions>
            {/* Disabled while the request runs, so a double click cannot send two deletes. */}
            <button type="button" className={primaryButton.red} onClick={onConfirm} disabled={isDeleting}>
                {isDeleting ? <Loader2 className="animate-spin h-5 w-5" aria-label="Deleting" /> : 'Delete'}
            </button>
            <button type="button" className={secondaryButton} onClick={onClose} disabled={isDeleting}>
                Cancel
            </button>
        </ModalActions>
    </Modal>
);

export default DeleteConfirmationModal;
