import { useEffect, useRef, useState } from 'react';
import { History, Loader2 } from 'lucide-react';
import api from '../services/api';
import { useToast } from '../hooks/useToast';
import { readableError } from '../utils/errors';
import { formatDate } from '../utils/date';
import { formatSize } from '../utils/format';
import Modal, { ModalActions } from './Modal';
import { secondaryButton } from './modalStyles';

const versionsUrl = (fileId) => `/api/files/${fileId}/versions`;

/**
 * Previous versions of a file, kept each time it is replaced. Restoring puts a version back in
 * place (the current content is kept as a new version first) or next to it as a copy.
 */
const VersionHistoryModal = ({ isOpen, onClose, file, onRestored }) => {
    const [versions, setVersions] = useState(null);
    const [error, setError] = useState(null);
    const [busy, setBusy] = useState(null);
    // Changes whenever the dialog is closed or shows another file. It stays mounted from one file
    // to the next, so a restore that finishes later must not close or mark up another file's view.
    const viewRef = useRef(0);
    const { addToast } = useToast();

    useEffect(() => {
        viewRef.current += 1;
        setBusy(null);
        if (!isOpen || !file) return undefined;
        let cancelled = false;
        setVersions(null);
        setError(null);
        api.get(versionsUrl(file.id))
            .then((response) => !cancelled && setVersions(response.data))
            .catch((err) => !cancelled && setError(readableError(err, 'Could not load the versions.')));
        return () => {
            cancelled = true;
        };
    }, [isOpen, file]);

    if (!file) return null;

    const restore = async (version, mode) => {
        const view = viewRef.current;
        const isCurrent = () => view === viewRef.current;
        setBusy(`${version}:${mode}`);
        setError(null);
        try {
            await api.post(`${versionsUrl(file.id)}/${version}/restore`, null, { params: { mode } });
            // The restore happened whichever file is on screen now, so report it and refresh.
            addToast(
                mode === 'COPY' ? `Version ${version} restored as a copy.` : `Version ${version} restored.`,
                'success',
            );
            onRestored?.();
            if (isCurrent()) onClose();
        } catch (err) {
            const message = readableError(err, 'Could not restore that version.');
            // Once the dialog has moved on, a toast is the only place left to say so.
            if (isCurrent()) setError(message);
            else addToast(message, 'error');
        } finally {
            if (isCurrent()) setBusy(null);
        }
    };

    return (
        <Modal
            isOpen={isOpen}
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
                {versions === null && !error && (
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
