import { useEffect } from 'react';
import { History, Loader2 } from 'lucide-react';
import { useListFileVersionsQuery, useRestoreVersionMutation } from '../services/apiSlice';
import { useToast } from '../hooks/useToast';
import { readableError } from '../utils/errors';
import { formatDate } from '../utils/date';
import { formatSize } from '../utils/format';
import Modal, { ModalActions } from './Modal';
import { secondaryButton } from './modalStyles';

/**
 * Previous versions of a file, kept each time it is replaced. Restoring puts a version back in
 * place (the current content is kept as a new version first) or next to it as a copy.
 */
const VersionHistoryModal = ({ isOpen, onClose, file }) =>
    // Mounted while open and keyed by file, so each opening has its own query and restore.
    isOpen && file ? <VersionHistoryDialog key={file.id} onClose={onClose} file={file} /> : null;

const VersionHistoryDialog = ({ onClose, file }) => {
    const { data: versions, error: loadError } = useListFileVersionsQuery({ fileId: file.id });
    const [restoreVersion, restoring] = useRestoreVersionMutation();
    const { addToast } = useToast();

    // Closes from the restore's own state, so a restore that finishes after this dialog has given way
    // to another file's never closes that one: an unmounted dialog runs no effects.
    useEffect(() => {
        if (restoring.isSuccess) onClose();
    }, [restoring.isSuccess, onClose]);

    // A restore changes the file whichever dialog is on screen by the time it finishes, so its
    // outcome is a toast, and the file list refreshes through its cache tag either way.
    const restore = async (version, mode) => {
        try {
            await restoreVersion({ fileId: file.id, version, mode }).unwrap();
            addToast(mode === 'COPY' ? `Version ${version} restored as a copy.` : `Version ${version} restored.`, 'success');
        } catch (err) {
            addToast(readableError(err, 'Could not restore that version.'), 'error');
        }
    };

    const busy = restoring.isLoading ? `${restoring.originalArgs.version}:${restoring.originalArgs.mode}` : null;
    const error = loadError ? readableError(loadError, 'Could not load the versions.') : null;

    return (
        <Modal
            isOpen
            onClose={onClose}
            title={
                <>
                    Versions of <span className="font-semibold">{file.name}</span>
                </>
            }
            icon={<History className="h-6 w-6 text-indigo-600" aria-hidden="true" />}
            iconClassName="bg-indigo-100"
        >
            <div className="mt-4">
                {versions === undefined && !error && (
                    <div className="flex justify-center py-6">
                        <Loader2 className="h-6 w-6 animate-spin text-gray-400" aria-label="Loading versions" />
                    </div>
                )}
                {versions?.length === 0 && (
                    <p className="text-sm text-gray-500">
                        No previous versions yet. One is kept each time this file is replaced.
                    </p>
                )}
                {versions?.length > 0 && (
                    <ul className="divide-y divide-gray-200 border border-gray-200 rounded-md max-h-80 overflow-y-auto">
                        {versions.map((v) => (
                            <li key={v.id} className="px-3 py-2 flex flex-wrap items-center gap-2 justify-between">
                                <div className="text-sm">
                                    <div className="font-medium text-gray-900">Version {v.version}</div>
                                    <div className="text-xs text-gray-500">
                                        {formatSize(v.size)} · {formatDate(v.createdAt)} · {v.createdBy}
                                    </div>
                                </div>
                                <div className="flex gap-2">
                                    <button
                                        type="button"
                                        disabled={busy !== null}
                                        onClick={() => restore(v.version, 'OVERWRITE')}
                                        className="text-xs font-medium px-2 py-1 rounded-md border border-indigo-200 text-indigo-700 hover:bg-indigo-50 disabled:opacity-50"
                                    >
                                        {busy === `${v.version}:OVERWRITE` ? 'Restoring…' : 'Restore'}
                                    </button>
                                    <button
                                        type="button"
                                        disabled={busy !== null}
                                        onClick={() => restore(v.version, 'COPY')}
                                        className="text-xs font-medium px-2 py-1 rounded-md border border-gray-300 text-gray-700 hover:bg-gray-50 disabled:opacity-50"
                                    >
                                        {busy === `${v.version}:COPY` ? 'Restoring…' : 'Restore as copy'}
                                    </button>
                                </div>
                            </li>
                        ))}
                    </ul>
                )}
                {error && <p className="mt-3 text-sm text-red-500">{error}</p>}
            </div>
            <ModalActions>
                <button type="button" className={secondaryButton} onClick={onClose}>
                    Done
                </button>
            </ModalActions>
        </Modal>
    );
};

export default VersionHistoryModal;
