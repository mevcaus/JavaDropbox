import { Download } from 'lucide-react';
import { PREVIEW_ENDPOINT } from '../features/filesSlice';
import FilePreview from './FilePreview';
import Modal, { ModalActions } from './Modal';
import { primaryButton, secondaryButton } from './modalStyles';

const previewUrl = (path) => `${PREVIEW_ENDPOINT}?path=${encodeURIComponent(path)}`;

/**
 * Shows a file in place: images and PDFs as themselves, text and source files as text. Which kind
 * a file is comes from the server (the tree's previewType), so the two agree on what opens.
 */
const PreviewModal = ({ isOpen, onClose, item, onDownload }) => {
    if (!item) return null;

    return (
        <Modal isOpen={isOpen} onClose={onClose} title={item.name} size="wide">
            <div className="mt-4">
                <FilePreview type={item.previewType} src={previewUrl(item.path)} name={item.name} />
            </div>
            <ModalActions>
                <button type="button" className={primaryButton.blue} onClick={() => onDownload(item)}>
                    <Download className="h-4 w-4 mr-2" aria-hidden="true" />
                    Download
                </button>
                <button type="button" className={secondaryButton} onClick={onClose}>
                    Done
                </button>
            </ModalActions>
        </Modal>
    );
};

export default PreviewModal;
