import { useRef, useState } from 'react';
import { FolderPlus } from 'lucide-react';
import Modal, { ModalActions } from './Modal';
import { primaryButton, secondaryButton } from './modalStyles';

const CreateFolderModal = ({ isOpen, onClose, onCreate }) => {
    const [folderName, setFolderName] = useState('');
    const inputRef = useRef(null);

    const handleClose = () => {
        setFolderName('');
        onClose();
    };

    const handleSubmit = (e) => {
        e.preventDefault();
        if (folderName.trim()) {
            onCreate(folderName.trim());
            handleClose();
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
                    <input
                        ref={inputRef}
                        type="text"
                        aria-label="Folder name"
                        className="shadow-sm focus:ring-green-500 focus:border-green-500 block w-full sm:text-sm border-gray-300 rounded-md p-2 border"
                        placeholder="Folder Name"
                        maxLength={255}
                        value={folderName}
                        onChange={(e) => setFolderName(e.target.value)}
                        required
                    />
                </div>
                <ModalActions>
                    <button type="submit" className={primaryButton.green}>
                        Create
                    </button>
                    <button type="button" className={secondaryButton} onClick={handleClose}>
                        Cancel
                    </button>
                </ModalActions>
            </form>
        </Modal>
    );
};

export default CreateFolderModal;
