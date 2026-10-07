import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { ChevronRight, Download, Link2Off, Loader2 } from 'lucide-react';
import api from '../services/api';
import FileIcon from '../components/FileIcon';
import FilePreview from '../components/FilePreview';
import Logo from '../components/Logo';
import { readableError } from '../utils/errors';
import { formatDate } from '../utils/date';
import { formatSize } from '../utils/format';

const itemCount = (count) => `${count} ${count === 1 ? 'item' : 'items'}`;

/**
 * What a shared folder holds, browsed in place: the folders in it open, and a trail of the folders
 * opened leads back. Read-only; the whole folder is downloaded as a zip.
 */
const FolderContents = ({ folder }) => {
    const [trail, setTrail] = useState([]);
    const entries = trail.reduce(
        (children, name) => children.find((entry) => entry.isDirectory && entry.name === name)?.children ?? [],
        folder.contents,
    );

    return (
        <div>
            <nav aria-label="Folder" className="flex flex-wrap items-center text-sm text-gray-500 mb-3">
                {[folder.name, ...trail].map((name, index) => {
                    const isLast = index === trail.length;
                    return (
                        <span key={index} className="flex items-center">
                            {index > 0 && <ChevronRight className="h-4 w-4 mx-1 text-gray-400" aria-hidden="true" />}
                            {isLast ? (
                                <span aria-current="page" className="font-semibold text-gray-900 break-all">
                                    {name}
                                </span>
                            ) : (
                                <button
                                    type="button"
                                    onClick={() => setTrail(trail.slice(0, index))}
                                    className="rounded-sm hover:text-blue-600 break-all focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
                                >
                                    {name}
                                </button>
                            )}
                        </span>
                    );
                })}
            </nav>

            {entries.length === 0 ? (
                <p className="py-6 text-center text-sm text-gray-500">This folder is empty.</p>
            ) : (
                <ul className="divide-y divide-gray-200 border border-gray-200 rounded-md">
                    {entries.map((entry) => (
                        <li key={entry.name} className="flex items-center gap-3 px-3 py-2">
                            <FileIcon type={entry.isDirectory ? 'DIRECTORY' : 'FILE'} name={entry.name} />
                            <div className="min-w-0 flex-1">
                                {entry.isDirectory ? (
                                    <button
                                        type="button"
                                        onClick={() => setTrail([...trail, entry.name])}
                                        className="text-left text-sm font-medium text-blue-600 hover:text-blue-800 hover:underline break-all focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
                                    >
                                        {entry.name}
                                    </button>
                                ) : (
                                    <span className="text-sm font-medium text-gray-900 break-all">{entry.name}</span>
                                )}
                            </div>
                            <span className="whitespace-nowrap text-xs text-gray-500">
                                {entry.isDirectory ? itemCount(entry.children.length) : formatSize(entry.size)}
                            </span>
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
};

/**
 * The page a share link opens in a browser: what was shared, previewed where the browser can show
 * it, with a button to download it. Nothing is downloaded until that button is pressed. Public, so
 * it only uses the link's own routes, never the signed-in API.
 */
const Shared = () => {
    const { token } = useParams();
    const link = `/share/${encodeURIComponent(token)}`;
    const [item, setItem] = useState(null);
    const [error, setError] = useState(null);

    useEffect(() => {
        let cancelled = false;
        api.get(`${link}/info`)
            .then((response) => !cancelled && setItem(response.data))
            .catch((err) => {
                if (cancelled) return;
                setError(err.response?.status === 404 ? 'gone' : readableError(err, 'Could not open this link.'));
            });
        return () => {
            cancelled = true;
        };
    }, [link]);

    useEffect(() => {
        if (!item) return undefined;
        const previous = document.title;
        document.title = `${item.name} · JavaDropbox`;
        return () => {
            document.title = previous;
        };
    }, [item]);

    let body;
    if (error === 'gone') {
        body = (
            <div className="text-center py-10">
                <Link2Off className="mx-auto h-10 w-10 text-gray-400" aria-hidden="true" />
                <h1 className="mt-4 text-xl font-semibold text-gray-900">This link doesn&apos;t work</h1>
                <p className="mt-2 text-sm text-gray-600">
                    It may have expired or been revoked, or what it shared may have been moved or deleted. Ask
                    whoever sent it for a new one.
                </p>
            </div>
        );
    } else if (error) {
        body = (
            <p role="alert" className="py-10 text-center text-sm text-red-500">
                {error}
            </p>
        );
    } else if (!item) {
        body = (
            <div className="flex justify-center py-16">
                <Loader2 className="h-8 w-8 animate-spin text-blue-500" aria-label="Loading" />
            </div>
        );
    } else {
        body = (
            <>
                <div className="flex flex-wrap items-start justify-between gap-4">
                    <div className="flex min-w-0 items-start gap-3">
                        <div className="mt-1 flex-shrink-0">
                            <FileIcon type={item.isDirectory ? 'DIRECTORY' : 'FILE'} name={item.name} />
                        </div>
                        <div className="min-w-0">
                            <h1 className="text-xl font-semibold text-gray-900 break-all">{item.name}</h1>
                            <p className="mt-1 text-sm text-gray-500">
                                {item.isDirectory
                                    ? `Folder · ${itemCount(item.contents.length)} · ${formatSize(item.size)}`
                                    : formatSize(item.size)}
                                {' · '}Link expires {formatDate(item.expiresAt)}
                            </p>
                        </div>
                    </div>
                    {/* A plain link, so the browser streams the download to disk itself. */}
                    <a
                        href={`${link}/download`}
                        download
                        className="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 shadow-sm"
                    >
                        <Download className="h-4 w-4 mr-2" aria-hidden="true" />
                        {item.isDirectory ? 'Download as .zip' : 'Download'}
                    </a>
                </div>

                <div className="mt-6">
                    {item.isDirectory ? (
                        <FolderContents folder={item} />
                    ) : item.previewType ? (
                        <FilePreview type={item.previewType} src={`${link}/preview`} name={item.name} />
                    ) : (
                        <p className="rounded-md bg-gray-50 py-10 text-center text-sm text-gray-500">
                            There&apos;s no preview for this kind of file. Download it to open it.
                        </p>
                    )}
                </div>
            </>
        );
    }

    return (
        <div className="min-h-screen bg-gray-50">
            <header className="bg-white shadow-sm">
                <div className="mx-auto max-w-5xl px-4 py-4 sm:px-6">
                    <Logo />
                </div>
            </header>
            <main className="mx-auto max-w-5xl px-4 py-8 sm:px-6">
                <div className="rounded-lg bg-white p-4 shadow sm:p-6">{body}</div>
                <p className="mt-4 text-center text-xs text-gray-500">
                    Shared with JavaDropbox. Anyone with this link can see and download it until it expires.
                </p>
            </main>
        </div>
    );
};

export default Shared;
