import { useEffect, useRef, useState } from 'react';
import { Link2, Copy, Check, Loader2 } from 'lucide-react';
import api from '../services/api';
import { useToast } from '../hooks/useToast';
import { readableError } from '../utils/errors';
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
    const { addToast } = useToast();

    useEffect(() => () => clearTimeout(copiedTimerRef.current), []);

    // Reset state whenever a new item is shared
    useEffect(() => {
        setShareUrl(null);
        setError(null);
        setCopied(false);
        setExpirationMinutes(EXPIRATION_OPTIONS[2].minutes);
    }, [item]);

    if (!item) return null;

    const handleGenerate = async () => {
        setLoading(true);
        setError(null);

        try {
            const params = new URLSearchParams();
            params.append('path', item.path);
            params.append('expirationMinutes', expirationMinutes);

            const response = await api.post('/api/share', params, {
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            });

            setShareUrl(response.data.url);
        } catch (err) {
            setError(readableError(err, 'Failed to create share link.'));
        } finally {
            setLoading(false);
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
                        Anyone with the link can download this {kind} until it expires. No account is required.
                        {item.isDirectory && ' The link always serves the folder as it is at the time of download, including files added later.'}{' '}
                        A link cannot be withdrawn before it expires.
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
                    </p>
                </div>
            )}
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
