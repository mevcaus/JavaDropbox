import { useEffect, useId, useRef, useState } from 'react';
import { Link2, Copy, Check, Loader2 } from 'lucide-react';
import api from '../services/api';
import { useToast } from '../hooks/useToast';
import { readableError } from '../utils/errors';
import { formatDate } from '../utils/date';
import Modal, { ModalActions } from './Modal';
import { primaryButton, secondaryButton } from './modalStyles';

const EXPIRATION_OPTIONS = [
    { label: '15 minutes', minutes: 15 },
    { label: '1 hour', minutes: 60 },
    { label: '24 hours', minutes: 60 * 24 },
    { label: '7 days', minutes: 60 * 24 * 7 },
];

const ShareModal = ({ isOpen, onClose, item }) => {
    const [expirationMinutes, setExpirationMinutes] = useState(EXPIRATION_OPTIONS[2].minutes);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);
    const [shareUrl, setShareUrl] = useState(null);
    const [copied, setCopied] = useState(false);
    const linkInputRef = useRef(null);
    const copiedTimerRef = useRef(null);
    // Counts requests and item changes. The dialog stays mounted from one share to the next, so a
    // slow answer for the previous item must not land in the dialog for this one.
    const requestRef = useRef(0);
    const { addToast } = useToast();

    useEffect(() => () => clearTimeout(copiedTimerRef.current), []);

    // Reset state whenever a new item is shared
    useEffect(() => {
        requestRef.current += 1;
        setShareUrl(null);
        setError(null);
        setLoading(false);
        setCopied(false);
        setExpirationMinutes(EXPIRATION_OPTIONS[2].minutes);
    }, [item]);

    if (!item) return null;

    const handleGenerate = async () => {
        const request = ++requestRef.current;
        const isCurrent = () => request === requestRef.current;
        setLoading(true);
        setError(null);

        try {
            const params = new URLSearchParams();
            params.append('path', item.path);
            params.append('expirationMinutes', expirationMinutes);

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

    const handleCopy = async () => {
        if (!shareUrl) return;
        if (await copyToClipboard(shareUrl, linkInputRef.current)) {
            setCopied(true);
            addToast('Link copied to clipboard', 'success');
            clearTimeout(copiedTimerRef.current);
            copiedTimerRef.current = setTimeout(() => setCopied(false), 2000);
        } else {
            addToast('Could not copy automatically. The link is selected: press Ctrl+C (or ⌘C) to copy it.', 'info');
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
                            value={expirationMinutes}
                            onChange={(e) => setExpirationMinutes(Number(e.target.value))}
                            className="block w-full rounded-md border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 sm:text-sm py-2 px-3 border"
                        >
                            {EXPIRATION_OPTIONS.map((opt) => (
                                <option key={opt.minutes} value={opt.minutes}>
                                    {opt.label}
                                </option>
                            ))}
                        </select>
                    </div>
                    <p className="text-sm text-gray-500">
                        Anyone with the link can download this {kind} until it expires or is revoked. No account is required.
                        {item.isDirectory && ' The link always serves the folder as it is at the time of download, including files added later.'}
                    </p>
                    {error && <p className="text-sm text-red-500">{error}</p>}
                </div>
            ) : (
                <div className="mt-4 space-y-3">
                    <div className="flex items-center gap-2">
                        <input
                            ref={linkInputRef}
                            type="text"
                            readOnly
                            aria-label="Share link"
                            value={shareUrl}
                            className="block w-full rounded-md border-gray-300 shadow-sm bg-gray-50 text-sm py-2 px-3 border"
                            onFocus={(e) => e.target.select()}
                        />
                        <button
                            type="button"
                            onClick={handleCopy}
                            className="flex-shrink-0 inline-flex items-center justify-center p-2 rounded-md border border-gray-300 text-gray-600 hover:bg-gray-50"
                            title="Copy link"
                        >
                            {copied ? <Check className="h-5 w-5 text-green-600" /> : <Copy className="h-5 w-5" />}
                        </button>
                    </div>
                    <p className="text-sm text-gray-500">
                        This link expires in {EXPIRATION_OPTIONS.find((o) => o.minutes === expirationMinutes)?.label || `${expirationMinutes} minutes`}.
                        Copy it now: it can't be shown again, though it can be revoked below.
                    </p>
                </div>
            )}
            <ActiveLinks path={item.path} reloadKey={shareUrl} />
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
// stored, so a link's URL cannot be shown again here. Reloaded when a new link is generated.
const ActiveLinks = ({ path, reloadKey }) => {
    const [links, setLinks] = useState([]);
    const [error, setError] = useState(null);
    const [revoking, setRevoking] = useState(null);
    const headingId = useId();
    const { addToast } = useToast();

    useEffect(() => {
        let cancelled = false;
        setLinks([]);
        setError(null);
        api.get('/api/share', { params: { path } })
            .then((response) => !cancelled && setLinks(response.data))
            .catch((err) => !cancelled && setError(readableError(err, 'Could not load the active links.')));
        return () => {
            cancelled = true;
        };
    }, [path, reloadKey]);

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

// The Clipboard API only exists in secure contexts, and a self-hosted install is often opened over
// plain http on a LAN address, where navigator.clipboard is undefined. Fall back to selecting the
// link and the legacy copy command, and report whether anything worked.
const copyToClipboard = async (text, input) => {
    if (navigator.clipboard?.writeText) {
        try {
            await navigator.clipboard.writeText(text);
            return true;
        } catch {
            // Permission denied or the document lost focus; try the fallback.
        }
    }
    input?.focus();
    input?.select();
    try {
        return document.execCommand?.('copy') ?? false;
    } catch {
        return false;
    }
};

export default ShareModal;
