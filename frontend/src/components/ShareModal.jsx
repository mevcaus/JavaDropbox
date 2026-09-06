import { useEffect, useState } from 'react';
import { Link2, Copy, Check, Loader2 } from 'lucide-react';
import api from '../services/api';
import { useToast } from '../contexts/ToastContext';

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
    const { addToast } = useToast();

    // Reset state whenever a new item is shared
    useEffect(() => {
        setShareUrl(null);
        setError(null);
        setCopied(false);
        setExpirationMinutes(EXPIRATION_OPTIONS[2].minutes);
    }, [item]);

    if (!isOpen || !item) return null;

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
            setError(err.response?.data?.message || 'Failed to create share link.');
        } finally {
            setLoading(false);
        }
    };

    const handleCopy = async () => {
        if (!shareUrl) return;
        await navigator.clipboard.writeText(shareUrl);
        setCopied(true);
        addToast('Link copied to clipboard', 'success');
        setTimeout(() => setCopied(false), 2000);
    };

    return (
        <div className="fixed inset-0 z-50 overflow-y-auto" aria-labelledby="modal-title" role="dialog" aria-modal="true">
            <div className="flex items-end justify-center min-h-screen pt-4 px-4 pb-20 text-center sm:block sm:p-0">
                <div className="fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity" aria-hidden="true" onClick={onClose}></div>

                <span className="hidden sm:inline-block sm:align-middle sm:h-screen" aria-hidden="true">&#8203;</span>

                <div className="inline-block align-bottom bg-white rounded-lg px-4 pt-5 pb-4 text-left overflow-hidden shadow-xl transform transition-all sm:my-8 sm:align-middle sm:max-w-lg sm:w-full sm:p-6">
                    <div className="sm:flex sm:items-start">
                        <div className="mx-auto flex-shrink-0 flex items-center justify-center h-12 w-12 rounded-full bg-blue-100 sm:mx-0 sm:h-10 sm:w-10">
                            <Link2 className="h-6 w-6 text-blue-600" aria-hidden="true" />
                        </div>
                        <div className="mt-3 text-center sm:mt-0 sm:ml-4 sm:text-left w-full">
                            <h3 className="text-lg leading-6 font-medium text-gray-900" id="modal-title">
                                Share <span className="font-semibold">{item.name}</span>
                            </h3>

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
                                        Anyone with the link can download this {item.isDirectory ? 'folder' : 'file'} until it expires. No account is required.
                                    </p>
                                    {error && <p className="text-sm text-red-500">{error}</p>}
                                </div>
                            ) : (
                                <div className="mt-4 space-y-3">
                                    <div className="flex items-center gap-2">
                                        <input
                                            type="text"
                                            readOnly
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
                        </div>
                    </div>
                    <div className="mt-5 sm:mt-4 sm:flex sm:flex-row-reverse">
                        {!shareUrl ? (
                            <button
                                type="button"
                                disabled={loading}
                                className="w-full inline-flex justify-center items-center rounded-md border border-transparent shadow-sm px-4 py-2 bg-blue-600 text-base font-medium text-white hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 sm:ml-3 sm:w-auto sm:text-sm disabled:opacity-50"
                                onClick={handleGenerate}
                            >
                                {loading ? <Loader2 className="animate-spin h-5 w-5" /> : 'Generate link'}
                            </button>
                        ) : null}
                        <button
                            type="button"
                            className="mt-3 w-full inline-flex justify-center rounded-md border border-gray-300 shadow-sm px-4 py-2 bg-white text-base font-medium text-gray-700 hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500 sm:mt-0 sm:w-auto sm:text-sm"
                            onClick={onClose}
                        >
                            {shareUrl ? 'Done' : 'Cancel'}
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default ShareModal;
