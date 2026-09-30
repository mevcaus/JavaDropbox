import { useId, useRef, useState } from 'react';
import { FolderPlus, Loader2 } from 'lucide-react';
import Modal, { ModalActions } from './Modal';
import { primaryButton, secondaryButton } from './modalStyles';

/**
 * Asks for a folder name. onCreate returns a promise: the dialog closes once it resolves, and stays
 * open with the typed name and the rejection's message otherwise (a 409 for a name already taken).
 */
const CreateFolderModal = ({ isOpen, onClose, onCreate }) => {
    const [folderName, setFolderName] = useState('');
    const [creating, setCreating] = useState(false);
    const [error, setError] = useState(null);
    const inputRef = useRef(null);
    const errorId = useId();

    const reset = () => {
        setFolderName('');
        setError(null);
    };

    const handleClose = () => {
        // Closing mid-request would hide the outcome; the request finishes either way.
        if (creating) return;
        reset();
        onClose();
    };

    const handleSubmit = async (e) => {
        e.preventDefault();
        const name = folderName.trim();
        if (!name || creating) return;

        setCreating(true);
        setError(null);
        try {
            await onCreate(name);
            reset();
            onClose();
        } catch (err) {
            setError(typeof err === 'string' ? err : 'Failed to create folder.');
            inputRef.current?.focus();
        } finally {
            setCreating(false);
        }
    };

    return (
        <Modal
            isOpen={isOpen}
            onClose={handleClose}
            title="Create New Folder"
            icon={<FolderPlus className="h-6 w-6 text-green-600" aria-hidden="true" />}
            iconClassName="bg-green-100"
            initialFocusRef={inputRef}
        >
            <form onSubmit={handleSubmit}>
                <div className="mt-2">
                    {/* Read-only rather than disabled while the request runs: disabling would take
                        focus away from it, and it gets focus back for a correction on failure. */}
                    <input
                        ref={inputRef}
                        type="text"
                        aria-label="Folder name"
                        aria-invalid={error ? true : undefined}
                        aria-describedby={error ? errorId : undefined}
                        className="shadow-sm focus:ring-green-500 focus:border-green-500 block w-full sm:text-sm border-gray-300 rounded-md p-2 border"
                        placeholder="Folder Name"
                        maxLength={255}
                        value={folderName}
                        readOnly={creating}
                        onChange={(e) => setFolderName(e.target.value)}
                        required
                    />
                    {error && (
                        <p id={errorId} role="alert" className="mt-2 text-sm text-red-600">
                            {error}
                        </p>
                    )}
                </div>
                <ModalActions>
                    <button type="submit" className={primaryButton.green} disabled={creating}>
                        {creating ? <Loader2 className="animate-spin h-5 w-5" aria-label="Creating" /> : 'Create'}
                    </button>
                    <button type="button" className={secondaryButton} onClick={handleClose} disabled={creating}>
                        Cancel
                    </button>
                </ModalActions>
            </form>
        </Modal>
    );
};

export default CreateFolderModal;
