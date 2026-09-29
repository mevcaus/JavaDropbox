import { useState, useMemo } from 'react';
import { Folder, Download, Share2, Trash2, FileText, Image, Film, Music, Search, ChevronUp, ChevronDown, History } from 'lucide-react';
import { formatDate, toTimestamp } from '../utils/date';
import { formatSize } from '../utils/format';

const FileIcon = ({ type, name }) => {
    if (type === 'DIRECTORY') return <Folder className="h-5 w-5 text-blue-500" />;

    const ext = name.split('.').pop().toLowerCase();

    // Images
    if (['jpg', 'jpeg', 'png', 'gif', 'svg', 'webp'].includes(ext)) return <Image className="h-5 w-5 text-purple-500" />;

    // Video
    if (['mp4', 'mov', 'avi', 'mkv', 'webm'].includes(ext)) return <Film className="h-5 w-5 text-red-500" />;

    // Audio
    if (['mp3', 'wav', 'ogg'].includes(ext)) return <Music className="h-5 w-5 text-green-500" />;

    // Code
    if (['js', 'jsx', 'ts', 'tsx', 'html', 'css', 'json', 'java', 'py', 'c', 'cpp'].includes(ext)) return <FileText className="h-5 w-5 text-yellow-500" />;

    return <FileText className="h-5 w-5 text-gray-500" />;
};

// Rendering every match of a loose query over a deep tree would lock the browser up, so cap it
const SEARCH_RESULT_LIMIT = 200;

// Searching reaches into every folder below the one being viewed. The API already hands the whole
// nested tree to the store, so this walks what is in memory rather than asking the server again.
const collectMatches = (nodes, lowerQuery, matches = []) => {
    for (const node of nodes) {
        if (node.name.toLowerCase().includes(lowerQuery)) {
            matches.push(node);
        }
        if (node.children && node.children.length > 0) {
            collectMatches(node.children, lowerQuery, matches);
        }
    }
    return matches;
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

const SortableHeader = ({ label, sortKey, sortConfig, onSort }) => {
    const isActive = sortConfig.key === sortKey;

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

const FileTable = ({ files, currentPath = '', onDelete, onDownload, onShare, onFolderClick, onVersions }) => {
    const [searchQuery, setSearchQuery] = useState('');
    const [sortConfig, setSortConfig] = useState({ key: 'name', direction: 'asc' });

    // Clear the filter when the user navigates to a different folder: a query typed against one
    // folder's contents would otherwise keep hiding the next folder's. Sort order is deliberately
    // kept across navigation, the way desktop file managers behave.
    const [pathAtLastRender, setPathAtLastRender] = useState(currentPath);
    if (currentPath !== pathAtLastRender) {
        setPathAtLastRender(currentPath);
        setSearchQuery('');
    }

    const handleSort = (key) => {
        setSortConfig((current) => ({
            key,
            direction: current.key === key && current.direction === 'asc' ? 'desc' : 'asc'
        }));
    };

    const query = searchQuery.trim().toLowerCase();

    // Browsing lists just this folder; searching flattens every match below it into one list,
    // each row labelled with its own path so you can tell which folder it came from.
    const { visibleFiles, matchCount, truncated } = useMemo(() => {
        if (!files) return { visibleFiles: [], matchCount: 0, truncated: false };

        const matched = query ? collectMatches(files, query) : files;
        const sorted = [...matched].sort((a, b) => compareNodes(a, b, sortConfig));

        return {
            visibleFiles: query ? sorted.slice(0, SEARCH_RESULT_LIMIT) : sorted,
            matchCount: sorted.length,
            truncated: Boolean(query) && sorted.length > SEARCH_RESULT_LIMIT,
        };
    }, [files, query, sortConfig]);

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
                    onChange={(e) => setSearchQuery(e.target.value)}
                    className="block w-full pl-9 pr-3 py-2 border border-gray-300 rounded-md leading-5 bg-white placeholder-gray-500 focus:outline-none focus:placeholder-gray-400 focus:ring-1 focus:ring-blue-500 focus:border-blue-500 sm:text-sm transition duration-150 ease-in-out"
                />
            </div>

            {query && (
                <p className="text-xs text-gray-500">
                    {matchCount === 0
                        ? 'No matches in this folder or the folders below it'
                        : `${matchCount} ${matchCount === 1 ? 'match' : 'matches'} in this folder and below${truncated ? ` — showing the first ${SEARCH_RESULT_LIMIT}` : ''}`}
                </p>
            )}

            <div className="bg-white shadow overflow-hidden sm:rounded-lg">
                <table className="min-w-full divide-y divide-gray-200">
                    <thead className="bg-gray-50">
                        <tr>
                            <SortableHeader label="Name" sortKey="name" sortConfig={sortConfig} onSort={handleSort} />
                            <SortableHeader label="Size" sortKey="size" sortConfig={sortConfig} onSort={handleSort} />
                            <SortableHeader label="Last Modified" sortKey="lastModified" sortConfig={sortConfig} onSort={handleSort} />
                            <th scope="col" className="relative px-6 py-3">
                                <span className="sr-only">Actions</span>
                            </th>
                        </tr>
                    </thead>
                    <tbody className="bg-white divide-y divide-gray-200">
                        {visibleFiles.length === 0 ? (
                            <tr>
                                <td colSpan="4" className="px-6 py-10 text-center text-gray-500">
                                    No files match your search.
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
                                                        className="text-sm font-medium text-blue-600 hover:text-blue-800 hover:underline focus:outline-none"
                                                    >
                                                        {file.name}
                                                    </button>
                                                ) : (
                                                    <div className="text-sm font-medium text-gray-900">{file.name}</div>
                                                )}
                                                <div className="text-xs text-gray-500">{file.relativePath}</div>
                                            </div>
                                        </div>
                                    </td>
                                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                                        {formatSize(file.size)}
                                    </td>
                                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                                        {formatDate(file.lastModified)}
                                    </td>
                                    {/* Revealed on hover, and whenever one of the buttons has keyboard focus. */}
                                    <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-medium opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity">
                                        {onVersions && file.id && !file.isDirectory && (
                                            <button
                                                onClick={(e) => {
                                                    e.stopPropagation();
                                                    onVersions(file);
                                                }}
                                                className="text-gray-500 hover:text-gray-800 mr-4"
                                                title="Versions"
                                                aria-label="Versions"
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
                                            aria-label="Download"
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
                                            aria-label="Share"
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
                                            aria-label="Delete"
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
