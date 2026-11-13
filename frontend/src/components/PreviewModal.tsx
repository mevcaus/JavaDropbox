import { Download } from 'lucide-react';
import { PREVIEW_ENDPOINT } from '../features/files';
import FilePreview, { type PreviewType } from './FilePreview';
import Modal, { ModalActions } from './Modal';
import { primaryButton, secondaryButton } from './modalStyles';

/** A file to preview: its name, its path from the root, and how it can be shown. */
export interface PreviewItem {
    name: string;
    path: string;
    previewType: PreviewType;
}

const previewUrl = (path: string) => `${PREVIEW_ENDPOINT}?path=${encodeURIComponent(path)}`;

/**
 * Shows a file in place: images and PDFs as themselves, text and source files as text. Which kind
 * a file is comes from the server (the tree's previewType), so the two agree on what opens.
 */
const PreviewModal = <T extends PreviewItem>({
    isOpen,
    onClose,
    item,
    onDownload,
}: {
    isOpen: boolean;
    onClose: () => void;
    item: T | null;
    onDownload: (item: T) => void;
}) => {
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
