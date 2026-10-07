import { Fragment, useState, useMemo } from 'react';
import { Download, Share2, Trash2, Search, ChevronUp, ChevronDown, History } from 'lucide-react';
import FileIcon from './FileIcon';
import { useFileSearch } from '../hooks/useFileSearch';
import { formatDate, toTimestamp } from '../utils/date';
import { formatSize } from '../utils/format';

// Every node below the open folder by its path. A search result is shown as the tree's own node
// where there is one, which carries what the result does not: the id that versions need, and a
// folder's size.
const indexByPath = (nodes, byPath = new Map()) => {
    for (const node of nodes) {
        byPath.set(node.relativePath, node);
        if (node.children && node.children.length > 0) {
            indexByPath(node.children, byPath);
        }
    }
    return byPath;
};

// The passage of a file's text that matched, with the matching words marked. The server gives
// plain text and where the matches are, so nothing in a file is ever rendered as markup.
const Snippet = ({ snippet }) => {
    const parts = [];
    let at = 0;
    for (const { start, end } of snippet.highlights) {
        if (start > at) parts.push({ text: snippet.text.slice(at, start) });
        parts.push({ text: snippet.text.slice(start, end), match: true });
        at = end;
    }
    if (at < snippet.text.length) parts.push({ text: snippet.text.slice(at) });

    return (
        <p className="mt-1 max-w-xl whitespace-normal break-words line-clamp-2 text-xs text-gray-600">
            {parts.map((part, i) => (
                <Fragment key={i}>
                    {part.match ? <mark className="rounded-sm bg-yellow-100 text-gray-900">{part.text}</mark> : part.text}
                </Fragment>
            ))}
        </p>
    );
};

const compareNodes = (a, b, { key, direction }) => {
    // Folders always stay grouped ahead of files; the direction only reorders within a group
    if (a.isDirectory && !b.isDirectory) return -1;
    if (!a.isDirectory && b.isDirectory) return 1;

    let aVal, bVal;
    if (key === 'size') {
        aVal = a.size || 0;
        bVal = b.size || 0;
    } else if (key === 'lastModified') {
        aVal = toTimestamp(a.lastModified);
        bVal = toTimestamp(b.lastModified);
    } else {
        aVal = a.name.toLowerCase();
        bVal = b.name.toLowerCase();
    }

    if (aVal < bVal) return direction === 'asc' ? -1 : 1;
    if (aVal > bVal) return direction === 'asc' ? 1 : -1;
    return 0;
};

// sortConfig is null while search results are in the server's order, best match first.
const SortableHeader = ({ label, sortKey, sortConfig, onSort }) => {
    const isActive = sortConfig?.key === sortKey;

    return (
        <th
            scope="col"
            aria-sort={isActive ? (sortConfig.direction === 'asc' ? 'ascending' : 'descending') : 'none'}
            className="p-0 text-left text-xs font-medium text-gray-500 uppercase tracking-wider"
        >
            {/* The button carries the cell padding so the whole header stays clickable, and is
                focusable so the column can be sorted from the keyboard as well as the mouse. */}
            <button
                type="button"
                onClick={() => onSort(sortKey)}
                className="w-full flex items-center px-6 py-3 uppercase select-none hover:bg-gray-200 focus:outline-none focus:ring-2 focus:ring-inset focus:ring-blue-500 transition-colors"
            >
                {label}
                {isActive ? (
                    sortConfig.direction === 'asc'
                        ? <ChevronUp className="h-4 w-4 ml-1 text-gray-700" />
                        : <ChevronDown className="h-4 w-4 ml-1 text-gray-700" />
                ) : (
                    // Placeholder keeps the header width stable as the arrow moves between columns
                    <span className="h-4 w-4 ml-1" aria-hidden="true" />
                )}
            </button>
        </th>
    );
};

// What a search found, or that it is still looking. Announced politely, so a screen reader hears
// the count once typing pauses rather than for every letter.
const SearchStatus = ({ answer, pending, shown, sortedByRelevance, onSortByRelevance }) => {
    let message;
    if (!answer) {
        message = 'Searching…';
    } else if (answer.error) {
        message = answer.error;
    } else if (pending) {
        message = 'Searching…';
    } else if (answer.total === 0) {
        message = 'No matches in this folder or the folders below it.';
    } else {
        const count = `${answer.total} ${answer.total === 1 ? 'match' : 'matches'} in this folder and below`;
        const order = sortedByRelevance ? ', best first' : '';
        const limited = shown < answer.total ? ` — showing the best ${shown}` : '';
        message = `${count}${order}${limited}.`;
    }

    return (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs">
            <p aria-live="polite" className={answer?.error ? 'text-red-600' : 'text-gray-500'}>
                {message}
                {answer?.indexing && !answer.error && ' Still indexing files, so some may be missing.'}
            </p>
            {!sortedByRelevance && answer && !answer.error && answer.total > 0 && (
                <button
                    type="button"
                    onClick={onSortByRelevance}
                    className="rounded-sm text-blue-600 hover:text-blue-800 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
                >
                    Sort by relevance
                </button>
            )}
        </div>
    );
};

const toggled = (current, key) => ({
    key,
    direction: current?.key === key && current.direction === 'asc' ? 'desc' : 'asc',
});

const FileTable = ({ files, currentPath = '', onDelete, onDownload, onShare, onFolderClick, onVersions, onPreview }) => {
    const [searchQuery, setSearchQuery] = useState('');
    const [sortConfig, setSortConfig] = useState({ key: 'name', direction: 'asc' });
    // How search results are sorted when not by relevance, which is how they arrive.
    const [resultSort, setResultSort] = useState(null);

    // Clear the search when the user navigates to a different folder: a query typed against one
    // folder's contents would otherwise keep hiding the next folder's. Sort order is deliberately
    // kept across navigation, the way desktop file managers behave.
    const [pathAtLastRender, setPathAtLastRender] = useState(currentPath);
    if (currentPath !== pathAtLastRender) {
        setPathAtLastRender(currentPath);
        setSearchQuery('');
        setResultSort(null);
    }

    const query = searchQuery.trim();
    const searching = query !== '';
    // The server searches names and the text inside files, everywhere below this folder.
    const { answer, pending } = useFileSearch(query, currentPath, files);

    const handleSearchChange = (e) => {
        setSearchQuery(e.target.value);
        // A new search starts out best first again.
        if (!e.target.value.trim()) setResultSort(null);
    };

    const handleSort = (key) => {
        if (searching) {
            setResultSort((current) => toggled(current, key));
        } else {
            setSortConfig((current) => toggled(current, key));
        }
    };

    const nodesByPath = useMemo(() => (searching && files ? indexByPath(files) : null), [files, searching]);

    // Browsing lists just this folder; searching lists the matches from every folder below it,
    // each row labelled with its own path so you can tell which folder it came from.
    const visibleFiles = useMemo(() => {
        if (!files) return [];
        if (!searching) return [...files].sort((a, b) => compareNodes(a, b, sortConfig));
        if (!answer) return [];

        const results = answer.results.map((result) => ({
            ...(nodesByPath.get(result.relativePath) ?? result),
            snippet: result.snippet,
        }));
        return resultSort ? results.sort((a, b) => compareNodes(a, b, resultSort)) : results;
    }, [files, searching, sortConfig, answer, nodesByPath, resultSort]);

    if (!files || files.length === 0) {
        return <div className="text-center py-10 text-gray-500">No files found.</div>;
    }

    return (
        <div className="space-y-4">
            {/* Search Input */}
            <div className="relative w-full md:w-64">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                    <Search className="h-4 w-4 text-gray-400" />
                </div>
                <input
                    type="text"
                    aria-label="Search files"
                    placeholder="Search files..."
                    value={searchQuery}
                    onChange={handleSearchChange}
                    className="block w-full pl-9 pr-3 py-2 border border-gray-300 rounded-md leading-5 bg-white placeholder-gray-500 focus:outline-none focus:placeholder-gray-400 focus:ring-1 focus:ring-blue-500 focus:border-blue-500 sm:text-sm transition duration-150 ease-in-out"
                />
            </div>

            {searching && (
                <SearchStatus
                    answer={answer}
                    pending={pending}
                    shown={visibleFiles.length}
                    sortedByRelevance={resultSort === null}
                    onSortByRelevance={() => setResultSort(null)}
                />
            )}

            {/* Scrolls sideways on a narrow screen, where the unwrapped columns are wider than the
                viewport; clipping instead would cut off the actions column. */}
            <div className="bg-white shadow overflow-x-auto sm:rounded-lg">
                <table className="min-w-full divide-y divide-gray-200">
                    <thead className="bg-gray-50">
                        <tr>
                            {[['Name', 'name'], ['Size', 'size'], ['Last Modified', 'lastModified']].map(([label, key]) => (
                                <SortableHeader
                                    key={key}
                                    label={label}
                                    sortKey={key}
                                    sortConfig={searching ? resultSort : sortConfig}
                                    onSort={handleSort}
                                />
                            ))}
                            <th scope="col" className="relative px-6 py-3">
                                <span className="sr-only">Actions</span>
                            </th>
                        </tr>
                    </thead>
                    <tbody className="bg-white divide-y divide-gray-200">
                        {visibleFiles.length === 0 ? (
                            <tr>
                                <td colSpan="4" className="px-6 py-10 text-center text-gray-500">
                                    {searching && !answer ? 'Searching…' : 'No files match your search.'}
                                </td>
                            </tr>
                        ) : (
                            visibleFiles.map((file) => (
                                <tr
                                    key={file.relativePath || file.name}
                                    className={`group transition-colors ${file.isDirectory ? 'cursor-pointer hover:bg-blue-50' : 'hover:bg-gray-50'}`}
                                    onClick={() => file.isDirectory && onFolderClick(file.relativePath)}
                                >
                                    <td className="px-6 py-4 whitespace-nowrap">
                                        <div className="flex items-center">
                                            <div className="flex-shrink-0 h-10 w-10 flex items-center justify-center">
                                                <FileIcon type={file.isDirectory ? 'DIRECTORY' : 'FILE'} name={file.name} />
                                            </div>
                                            <div className="ml-4">
                                                {file.isDirectory ? (
                                                    <button
                                                        onClick={(e) => {
                                                            e.stopPropagation(); // Prevent double trigger
                                                            onFolderClick(file.relativePath);
                                                        }}
                                                        className="rounded-sm text-sm font-medium text-blue-600 hover:text-blue-800 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2"
                                                    >
                                                        {file.name}
                                                    </button>
                                                ) : onPreview && file.previewType ? (
                                                    // The server marks which files a browser can show (previewType);
                                                    // the rest stay plain text and are only downloaded.
                                                    <button
                                                        onClick={(e) => {
                                                            e.stopPropagation();
                                                            onPreview(file);
                                                        }}
                                                        title="Preview"
                                                        aria-label={`Preview ${file.name}`}
                                                        className="rounded-sm text-left text-sm font-medium text-gray-900 hover:text-blue-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2"
                                                    >
                                                        {file.name}
                                                    </button>
                                                ) : (
                                                    <div className="text-sm font-medium text-gray-900">{file.name}</div>
                                                )}
                                                <div className="text-xs text-gray-500">{file.relativePath}</div>
                                                {searching && file.snippet && <Snippet snippet={file.snippet} />}
                                            </div>
                                        </div>
                                    </td>
                                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                                        {formatSize(file.size)}
                                    </td>
                                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                                        {formatDate(file.lastModified)}
                                    </td>
                                    {/* Each action names its file: a screen reader listing the buttons, or
                                        tabbing through them, would otherwise hear only "Download, Share,
                                        Delete" over and over.
                                        Where the device can hover, revealed on hover and whenever one of the
                                        buttons has keyboard focus. Touch screens cannot hover, so there
                                        the actions are always shown. */}
                                    <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-medium [@media(hover:hover)]:opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity">
                                        {onVersions && file.id && !file.isDirectory && (
                                            <button
                                                onClick={(e) => {
                                                    e.stopPropagation();
                                                    onVersions(file);
                                                }}
                                                className="text-gray-500 hover:text-gray-800 mr-4"
                                                title="Versions"
                                                aria-label={`Versions of ${file.name}`}
                                            >
                                                <History className="h-5 w-5" />
                                            </button>
                                        )}
                                        <button
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                onDownload(file);
                                            }}
                                            className="text-indigo-600 hover:text-indigo-900 mr-4"
                                            title="Download"
                                            aria-label={`Download ${file.name}`}
                                        >
                                            <Download className="h-5 w-5" />
                                        </button>
                                        <button
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                onShare(file);
                                            }}
                                            className="text-blue-600 hover:text-blue-900 mr-4"
                                            title="Share"
                                            aria-label={`Share ${file.name}`}
                                        >
                                            <Share2 className="h-5 w-5" />
                                        </button>
                                        <button
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                onDelete(file);
                                            }}
                                            className="text-red-600 hover:text-red-900"
                                            title="Delete"
                                            aria-label={`Delete ${file.name}`}
                                        >
                                            <Trash2 className="h-5 w-5" />
                                        </button>
                                    </td>
                                </tr>
                            ))
                        )}
                    </tbody>
                </table>
            </div>
        </div>
    );
};

export default FileTable;
