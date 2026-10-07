import { Folder, FileText, Image, Film, Music } from 'lucide-react';

// An icon for a folder, or for a file by its extension. Shared by the file table and share pages.
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

export default FileIcon;
