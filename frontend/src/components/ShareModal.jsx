import { useEffect, useId, useRef, useState } from 'react';
import { Link2, Loader2 } from 'lucide-react';
import api from '../services/api';
import { useToast } from '../hooks/useToast';
import { useDemoInfo } from '../hooks/useDemoInfo';
import { readableError } from '../utils/errors';
import { formatDate } from '../utils/date';
import Modal, { ModalActions } from './Modal';
import CopyLinkField from './CopyLinkField';
import { primaryButton, secondaryButton } from './modalStyles';

const EXPIRATION_OPTIONS = [
    { label: '15 minutes', minutes: 15 },
    { label: '1 hour', minutes: 60 },
    { label: '24 hours', minutes: 60 * 24 },
    { label: '7 days', minutes: 60 * 24 * 7 },
];

// The choices a server allowing links of at most maxMinutes accepts (all of them when unlimited).
const expirationOptions = (maxMinutes) => {
    if (!maxMinutes) return EXPIRATION_OPTIONS;
    const allowed = EXPIRATION_OPTIONS.filter((opt) => opt.minutes <= maxMinutes);
    return allowed.length > 0 ? allowed : [{ label: `${maxMinutes} minutes`, minutes: maxMinutes }];
};

// Keyed by path, so sharing another item starts from a fresh dialog: no link, error or spinner from
// the previous one carries over, and its late answers land in a dialog that is gone.
const ShareModal = ({ isOpen, onClose, item }) =>
    item ? <ShareDialog key={item.path} isOpen={isOpen} onClose={onClose} item={item} /> : null;

const ShareDialog = ({ isOpen, onClose, item }) => {
    const [expirationMinutes, setExpirationMinutes] = useState(EXPIRATION_OPTIONS[2].minutes);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);
    const [shareUrl, setShareUrl] = useState(null);
    // Counts requests, so only the latest one's answer is shown.
    const requestRef = useRef(0);
    // The public demo only allows short links. Until its limits have loaded, all choices show.
    const options = expirationOptions(useDemoInfo()?.maxShareMinutes);
    // A choice the limit rules out (such as the default) falls back to the longest one allowed.
    const minutes = options.some((opt) => opt.minutes === expirationMinutes)
        ? expirationMinutes
        : options[options.length - 1].minutes;

    const handleGenerate = async () => {
        const request = ++requestRef.current;
        const isCurrent = () => request === requestRef.current;
        setLoading(true);
        setError(null);

        try {
            const params = new URLSearchParams();
            params.append('path', item.path);
            params.append('expirationMinutes', minutes);

            const response = await api.post('/api/share', params, {
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            });

            if (isCurrent()) setShareUrl(response.data.url);
        } catch (err) {
            if (isCurrent()) setError(readableError(err, 'Failed to create share link.'));
        } finally {
            if (isCurrent()) setLoading(false);
        }
    };

    const kind = item.isDirectory ? 'folder' : 'file';

    return (
        <Modal
            isOpen={isOpen}
            onClose={onClose}
            title={
                <>
                    Share <span className="font-semibold">{item.name}</span>
                </>
            }
            icon={<Link2 className="h-6 w-6 text-blue-600" aria-hidden="true" />}
            iconClassName="bg-blue-100"
        >
            {!shareUrl ? (
                <div className="mt-4 space-y-4">
                    <div>
                        <label htmlFor="expiration" className="block text-sm font-medium text-gray-700 mb-1">
                            Link expires in
                        </label>
                        <select
                            id="expiration"
                            value={minutes}
                            onChange={(e) => setExpirationMinutes(Number(e.target.value))}
                            className="block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 sm:text-sm py-2 px-3 border"
                        >
                            {options.map((opt) => (
                                <option key={opt.minutes} value={opt.minutes}>
                                    {opt.label}
                                </option>
                            ))}
                        </select>
                    </div>
                    <p className="text-sm text-gray-500">
                        Anyone with the link can see and download this {kind} until it expires or is revoked. No account is required.
                        {item.isDirectory && ' The link always serves the folder as it is at the time of download, including files added later.'}
                    </p>
                    {error && <p className="text-sm text-red-500">{error}</p>}
                </div>
            ) : (
                <div className="mt-4 space-y-3">
                    <CopyLinkField url={shareUrl} label="Share link" />
                    <p className="text-sm text-gray-500">
                        This link expires in {options.find((o) => o.minutes === minutes)?.label || `${minutes} minutes`}.
                        Copy it now: it can't be shown again, though it can be revoked below.
                    </p>
                </div>
            )}
            {/* Keyed by the new link, so generating one loads the list again. */}
            <ActiveLinks key={shareUrl} path={item.path} />
            <ModalActions>
                {!shareUrl && (
                    <button type="button" disabled={loading} className={primaryButton.blue} onClick={handleGenerate}>
                        {loading ? <Loader2 className="animate-spin h-5 w-5" /> : 'Generate link'}
                    </button>
                )}
                <button type="button" className={secondaryButton} onClick={onClose}>
                    {shareUrl ? 'Done' : 'Cancel'}
                </button>
            </ModalActions>
        </Modal>
    );
};

// The item's links that still work, each with a Revoke button. Only a hash of a link's token is
// stored, so a link's URL cannot be shown again here.
const ActiveLinks = ({ path }) => {
    const [links, setLinks] = useState([]);
    const [error, setError] = useState(null);
    const [revoking, setRevoking] = useState(null);
    const headingId = useId();
    const { addToast } = useToast();

    useEffect(() => {
        let cancelled = false;
        api.get('/api/share', { params: { path } })
            .then((response) => !cancelled && setLinks(response.data))
            .catch((err) => !cancelled && setError(readableError(err, 'Could not load the active links.')));
        return () => {
            cancelled = true;
        };
    }, [path]);

    const revoke = async (link) => {
        setRevoking(link.id);
        setError(null);
        try {
            await api.delete(`/api/share/${link.id}`);
            setLinks((current) => current.filter((l) => l.id !== link.id));
            addToast('Link revoked', 'success');
        } catch (err) {
            setError(readableError(err, 'Could not revoke the link.'));
        } finally {
            setRevoking(null);
        }
    };

    if (links.length === 0 && !error) return null;

    return (
        <div className="mt-4">
            {links.length > 0 && (
                <>
                    <h4 id={headingId} className="text-sm font-medium text-gray-700 mb-1">
                        Active links
                    </h4>
                    <ul
                        aria-labelledby={headingId}
                        className="divide-y divide-gray-200 border border-gray-200 rounded-md max-h-48 overflow-y-auto"
                    >
                        {links.map((link) => (
                            <li key={link.id} className="px-3 py-2 flex items-center gap-2 justify-between">
                                <div className="text-sm">
                                    <div className="text-gray-900">Expires {formatDate(link.expiresAt)}</div>
                                    <div className="text-xs text-gray-500">
                                        Created {formatDate(link.createdAt)} · {link.createdBy}
                                    </div>
                                </div>
                                <button
                                    type="button"
                                    disabled={revoking !== null}
                                    onClick={() => revoke(link)}
                                    aria-label={`Revoke the link that expires ${formatDate(link.expiresAt)}`}
                                    className="text-xs font-medium px-2 py-1 rounded-md border border-red-200 text-red-700 hover:bg-red-50 disabled:opacity-50"
                                >
                                    {revoking === link.id ? 'Revoking…' : 'Revoke'}
                                </button>
                            </li>
                        ))}
                    </ul>
                </>
            )}
            {error && <p className="mt-2 text-sm text-red-500">{error}</p>}
        </div>
    );
};

export default ShareModal;
