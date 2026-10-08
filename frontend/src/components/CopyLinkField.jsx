import { useEffect, useRef, useState } from 'react';
import { Copy, Check } from 'lucide-react';
import { useToast } from '../hooks/useToast';
import { copyToClipboard } from '../utils/clipboard';

/** A link the server shows once, such as a share link or an invitation, with a button to copy it. */
const CopyLinkField = ({ url, label }) => {
    const [copied, setCopied] = useState(false);
    const inputRef = useRef(null);
    const copiedTimerRef = useRef(null);
    const { addToast } = useToast();

    useEffect(() => () => clearTimeout(copiedTimerRef.current), []);

    const handleCopy = async () => {
        if (await copyToClipboard(url, inputRef.current)) {
            setCopied(true);
            addToast('Link copied to clipboard', 'success');
            clearTimeout(copiedTimerRef.current);
            copiedTimerRef.current = setTimeout(() => setCopied(false), 2000);
        } else {
            addToast('Could not copy automatically. The link is selected: press Ctrl+C (or ⌘C) to copy it.', 'info');
        }
    };

    return (
        <div className="flex items-center gap-2">
            <input
                ref={inputRef}
                type="text"
                readOnly
                aria-label={label}
                value={url}
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
    );
};

export default CopyLinkField;
