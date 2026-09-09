import { useState, useMemo } from 'react';
import { File, Folder, Download, Share2, Trash2, FileText, Image, Film, Music, Search, ChevronUp, ChevronDown } from 'lucide-react';

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
    if (['js', 'jsx', 'ts', 'tsx', 'html', 'css', 'json', 'java', 'py', 'c', 'cpp'].includes(ext)) return <FileText className="h-5 w-5 text-yellow-500" />; // Or specific Code icon if available in Lucide, 'Code' is available

    return <FileText className="h-5 w-5 text-gray-500" />;
};

const formatSize = (bytes) => {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
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

const FileTable = ({ files, onDelete, onDownload, onShare, onFolderClick }) => {
    const [searchQuery, setSearchQuery] = useState('');
    const [sortConfig, setSortConfig] = useState({ key: 'name', direction: 'asc' });

    const handleSort = (key) => {
        setSortConfig((current) => ({
            key,
            direction: current.key === key && current.direction === 'asc' ? 'desc' : 'asc'
        }));
    };

    const processedFiles = useMemo(() => {
        if (!files) return [];
        let result = files;

        if (searchQuery) {
            const lowerQuery = searchQuery.toLowerCase();
            result = result.filter(f => f.name.toLowerCase().includes(lowerQuery));
        }

        if (sortConfig.key) {
            result = [...result].sort((a, b) => {
                // Folders always stay grouped ahead of files; the direction only reorders within a group
                if (a.isDirectory && !b.isDirectory) return -1;
                if (!a.isDirectory && b.isDirectory) return 1;

                let aVal, bVal;
                if (sortConfig.key === 'name') {
                    aVal = a.name.toLowerCase();
                    bVal = b.name.toLowerCase();
                } else if (sortConfig.key === 'size') {
                    aVal = a.size || 0;
                    bVal = b.size || 0;
                } else if (sortConfig.key === 'lastModified') {
                    aVal = a.lastModified ? new Date(a.lastModified).getTime() : 0;
                    bVal = b.lastModified ? new Date(b.lastModified).getTime() : 0;
                }

                if (aVal < bVal) return sortConfig.direction === 'asc' ? -1 : 1;
                if (aVal > bVal) return sortConfig.direction === 'asc' ? 1 : -1;
                return 0;
            });
        }
        return result;
    }, [files, searchQuery, sortConfig]);

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

            <div className="bg-white shadow overflow-hidden sm:rounded-lg">
                <table className="min-w-full divide-y divide-gray-200">
                    <thead className="bg-gray-50">
                        <tr>
                            <SortableHeader label="Name" sortKey="name" sortConfig={sortConfig} onSort={handleSort} />
                            <SortableHeader label="Size" sortKey="size" sortConfig={sortConfig} onSort={handleSort} />
                            <th scope="col" className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                                Access
                            </th>
                            <SortableHeader label="Last Modified" sortKey="lastModified" sortConfig={sortConfig} onSort={handleSort} />
                            <th scope="col" className="relative px-6 py-3">
                                <span className="sr-only">Actions</span>
                            </th>
                        </tr>
                    </thead>
                    <tbody className="bg-white divide-y divide-gray-200">
                        {processedFiles.length === 0 ? (
                            <tr>
                                <td colSpan="5" className="px-6 py-10 text-center text-gray-500">
                                    No files match your search.
                                </td>
                            </tr>
                        ) : (
                            processedFiles.map((file) => (
                                <tr
                                    key={file.relativePath || file.name}
                                    className={`group transition-colors ${file.isDirectory ? 'cursor-pointer hover:bg-blue-50' : 'hover:bg-gray-50'}`}
                                    onClick={() => file.isDirectory && onFolderClick(file.name)}
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
                                                            onFolderClick(file.name);
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
                                        <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-800">
                                            Only You
                                        </span>
                                    </td>
                                    <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                                        {file.lastModified || '-'}
                                    </td>
                                    <td className="px-6 py-4 whitespace-nowrap text-right text-sm font-medium opacity-0 group-hover:opacity-100 transition-opacity">
                                        <button
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                onDownload(file);
                                            }}
                                            className="text-indigo-600 hover:text-indigo-900 mr-4"
                                            title="Download"
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
