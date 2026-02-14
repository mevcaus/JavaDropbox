import { File, Folder, Download, Trash2, FileText, Image, Film, Music } from 'lucide-react';

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

const FileTable = ({ files, onDelete, onDownload, onFolderClick }) => {
    if (!files || files.length === 0) {
        return <div className="text-center py-10 text-gray-500">No files found.</div>;
    }

    return (
        <div className="bg-white shadow overflow-hidden sm:rounded-lg">
            <table className="min-w-full divide-y divide-gray-200">
                <thead className="bg-gray-50">
                    <tr>
                        <th scope="col" className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                            Name
                        </th>
                        <th scope="col" className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                            Size
                        </th>
                        <th scope="col" className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                            Access
                        </th>
                        <th scope="col" className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                            Last Modified
                        </th>
                        <th scope="col" className="relative px-6 py-3">
                            <span className="sr-only">Actions</span>
                        </th>
                    </tr>
                </thead>
                <tbody className="bg-white divide-y divide-gray-200">
                    {files.map((file) => (
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
                                        onDelete(file);
                                    }}
                                    className="text-red-600 hover:text-red-900"
                                    title="Delete"
                                >
                                    <Trash2 className="h-5 w-5" />
                                </button>
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
};

export default FileTable;
