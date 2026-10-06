import { useEffect, useState } from 'react';
import { Loader2 } from 'lucide-react';
import api from '../services/api';
import { readableError } from '../utils/errors';
import { formatSize } from '../utils/format';

// Only the start of a text file is fetched: a multi-gigabyte log would otherwise be pulled into
// the tab whole just to show its first screen.
const TEXT_PREVIEW_BYTES = 256 * 1024;

// "bytes 0-262143/1048576" -> 1048576; null when the header is missing or the size unknown ("*").
const totalSize = (contentRange) => {
    const total = /\/(\d+)$/.exec(contentRange ?? '')?.[1];
    return total === undefined ? null : Number(total);
};

// The text request asks for a string, so an error's JSON body arrives unparsed too.
const parsedError = (error) => {
    const data = error?.response?.data;
    if (typeof data !== 'string') return error;
    try {
        return { ...error, response: { ...error.response, data: JSON.parse(data) } };
    } catch {
        return error;
    }
};

const loadText = async (src, signal) => {
    let response;
    try {
        response = await api.get(src, {
            headers: { Range: `bytes=0-${TEXT_PREVIEW_BYTES - 1}` },
            // Kept as a string: a .json file must show as written, not be parsed by axios.
            responseType: 'text',
            signal,
        });
    } catch (error) {
        // Asking for the first byte of an empty file is a range that cannot be satisfied.
        if (error.response?.status === 416) return { content: '', total: 0, truncated: false };
        throw error;
    }

    const total = totalSize(response.headers?.['content-range']);
    const truncated = response.status === 206 && total !== null && total > TEXT_PREVIEW_BYTES;
    let content = response.data;
    if (truncated) {
        // The cut can fall inside a line, or inside a multi-byte character; end on the last whole line.
        const lastNewline = content.lastIndexOf('\n');
        if (lastNewline > 0) content = content.slice(0, lastNewline);
    }
    return { content, total, truncated };
};

const TextPreview = ({ src, name }) => {
    const [result, setResult] = useState(null);
    const [error, setError] = useState(null);

    useEffect(() => {
        const controller = new AbortController();
        loadText(src, controller.signal)
            .then(setResult)
            .catch((err) => {
                if (!controller.signal.aborted) {
                    setError(readableError(parsedError(err), 'Could not load the preview.'));
                }
            });
        return () => controller.abort();
    }, [src]);

    if (error) return <p className="text-sm text-red-500">{error}</p>;
    if (!result) {
        return (
            <div className="flex justify-center py-10">
                <Loader2 className="h-6 w-6 animate-spin text-gray-400" aria-label="Loading preview" />
            </div>
        );
    }
    if (result.content === '') return <p className="text-sm text-gray-500">This file is empty.</p>;

    return (
        <>
            {result.truncated && (
                <p className="mb-2 text-xs text-gray-500">
                    Showing the first {formatSize(TEXT_PREVIEW_BYTES)} of {formatSize(result.total)}. Download the
                    file to see all of it.
                </p>
            )}
            {/* Focusable so the keyboard can scroll it; React escapes the content, so markup in
                the file is shown as text. */}
            <pre
                tabIndex={0}
                aria-label={`Contents of ${name}`}
                className="max-h-[70vh] overflow-auto rounded-md border border-gray-200 bg-gray-50 p-3 text-xs leading-relaxed text-gray-800 whitespace-pre font-mono focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
            >
                {result.content}
            </pre>
        </>
    );
};

const ImagePreview = ({ src, name }) => {
    const [failed, setFailed] = useState(false);

    if (failed) return <p className="text-sm text-red-500">Could not load the image.</p>;
    return (
        <div className="flex justify-center rounded-md bg-gray-100 p-2">
            <img
                src={src}
                alt={name}
                onError={() => setFailed(true)}
                className="max-h-[70vh] max-w-full object-contain"
            />
        </div>
    );
};

const PdfPreview = ({ src, name }) => (
    <iframe src={src} title={`Preview of ${name}`} className="h-[70vh] w-full rounded-md border border-gray-200" />
);

const PREVIEWS = {
    image: ImagePreview,
    pdf: PdfPreview,
    text: TextPreview,
};

/**
 * Shows a file in place from a URL that serves it inline: images and PDFs as themselves, text and
 * source files as text. Which kind a file is comes from the server (its previewType), so the two
 * agree on what opens. Used for the signed-in preview and for share links' pages.
 */
const FilePreview = ({ type, src, name }) => {
    const Preview = PREVIEWS[type];
    if (!Preview) return <p className="text-sm text-gray-500">This kind of file cannot be previewed.</p>;
    // Keyed by URL, so moving to another file starts from a clean loading state.
    return <Preview key={src} src={src} name={name} />;
};

export default FilePreview;
