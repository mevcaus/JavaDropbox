import { useEffect, useState } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { useSearchParams } from 'react-router-dom';
import {
    fetchFiles,
    deleteItem,
    selectCurrentFiles,
    createDirectory,
    uploadFiles,
    DOWNLOAD_ENDPOINT,
} from '../features/filesSlice';
import FileTable from '../components/FileTable';
import Breadcrumbs from '../components/Breadcrumbs';
import DeleteConfirmationModal from '../components/DeleteConfirmationModal';
import CreateFolderModal from '../components/CreateFolderModal';
import ShareModal from '../components/ShareModal';
import VersionHistoryModal from '../components/VersionHistoryModal';
import PreviewModal from '../components/PreviewModal';
import { useToast } from '../hooks/useToast';
import { DOT_NAME_RULE, startsWithDot } from '../utils/names';
import { Loader2, FolderPlus, Upload as UploadIcon } from 'lucide-react';

// Search results carry their own relativePath; a plain row in this folder may not.
const pathOf = (file, currentPath) =>
    file.relativePath || (currentPath ? `${currentPath}/${file.name}` : file.name);

const Dashboard = () => {
    const dispatch = useDispatch();
    // The folder being viewed lives in the URL, so a reload keeps it and Back/Forward move
    // between folders.
    const [searchParams, setSearchParams] = useSearchParams();
    const currentPath = searchParams.get('path') ?? '';
    const files = useSelector((state) => selectCurrentFiles(state, currentPath));
    const { loading, loaded, error } = useSelector((state) => state.files);
    const { addToast } = useToast();

    const [itemToDelete, setItemToDelete] = useState(null);
    const [isDeleting, setIsDeleting] = useState(false);
    const [isCreateFolderModalOpen, setIsCreateFolderModalOpen] = useState(false);
    const [itemToShare, setItemToShare] = useState(null);
    const [versionsFile, setVersionsFile] = useState(null);
    const [previewItem, setPreviewItem] = useState(null);
    const [isUploading, setIsUploading] = useState(false);

    useEffect(() => {
        dispatch(fetchFiles());
    }, [dispatch]);

    // FileTable hands back the folder's full path, which is what search results need: a nested
    // match cannot be located by name alone.
    const handleNavigate = (path) => {
        setSearchParams(path ? { path } : {});
    };

    // A failure propagates to CreateFolderModal, which stays open and shows it next to the name.
    const handleCreateFolder = async (folderName) => {
        await dispatch(createDirectory({ path: currentPath, name: folderName })).unwrap();
        addToast(`Folder "${folderName}" created successfully.`, 'success');
    };

    const handleFileUpload = async (e) => {
        const input = e.target;
        const chosen = Array.from(input.files ?? []);
        // Clear the input so choosing the same file again still fires a change event.
        input.value = '';

        const skipped = chosen.filter((file) => startsWithDot(file.name));
        if (skipped.length > 0) {
            const names = skipped.map((file) => file.name).join(', ');
            addToast(`${names} ${skipped.length === 1 ? 'was' : 'were'} not uploaded. ${DOT_NAME_RULE}`, 'error');
        }
        const selected = chosen.filter((file) => !startsWithDot(file.name));
        if (selected.length === 0) return;

        setIsUploading(true);
        try {
            await dispatch(uploadFiles({ files: selected, path: currentPath })).unwrap();
            addToast(`Uploaded ${selected.length} ${selected.length === 1 ? 'file' : 'files'} successfully.`, 'success');
        } catch (err) {
            addToast(err, 'error');
        } finally {
            setIsUploading(false);
        }
    };

    const handleDelete = async () => {
        if (!itemToDelete) return;
        setIsDeleting(true);
        try {
            await dispatch(deleteItem(pathOf(itemToDelete, currentPath))).unwrap();
            addToast(`"${itemToDelete.name}" deleted successfully.`, 'success');
        } catch (err) {
            addToast(err, 'error');
        } finally {
            setIsDeleting(false);
            setItemToDelete(null);
        }
    };

    // A plain link rather than fetching the file into memory: the browser streams it to disk, so
    // a large file or folder zip never has to fit in the tab. The session cookie goes with it.
    const handleDownload = (file) => {
        const link = document.createElement('a');
        link.href = `${DOWNLOAD_ENDPOINT}?path=${encodeURIComponent(pathOf(file, currentPath))}`;
        link.download = '';
        document.body.appendChild(link);
        link.click();
        link.remove();
    };

    const handlePreview = (file) => {
        setPreviewItem({ ...file, path: pathOf(file, currentPath) });
    };

    const handleShare = (file) => {
        setItemToShare({ name: file.name, isDirectory: file.isDirectory, path: pathOf(file, currentPath) });
    };

    if (loading && !loaded) {
        return (
            <div className="flex items-center justify-center h-64">
                <Loader2 className="h-8 w-8 animate-spin text-blue-500" aria-label="Loading files" />
            </div>
        );
    }

    if (error && !loaded) {
        return <div className="text-red-500 text-center py-4">Error loading files: {error}</div>;
    }

    return (
        <div>
            <div className="mb-6 flex flex-wrap gap-3 items-center justify-between">
                <h1 className="text-2xl font-semibold text-gray-900">My Files</h1>
                <div className="flex space-x-3">
                    <label
                        className={`flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus-within:ring-2 focus-within:ring-offset-2 focus-within:ring-blue-500 shadow-sm transition-colors ${
                            isUploading ? 'opacity-50 cursor-wait' : 'cursor-pointer'
                        }`}
                    >
                        {isUploading ? (
                            <Loader2 className="h-4 w-4 mr-2 animate-spin" aria-hidden="true" />
                        ) : (
                            <UploadIcon className="h-4 w-4 mr-2" aria-hidden="true" />
                        )}
                        {isUploading ? 'Uploading…' : 'Upload'}
                        <input
                            type="file"
                            className="sr-only"
                            multiple
                            disabled={isUploading}
                            onChange={handleFileUpload}
                        />
                    </label>

                    <button
                        onClick={() => setIsCreateFolderModalOpen(true)}
                        className="flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-green-600 hover:bg-green-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-green-500 shadow-sm transition-colors"
                    >
                        <FolderPlus className="h-4 w-4 mr-2" aria-hidden="true" />
                        New Folder
                    </button>
                </div>
            </div>

            <Breadcrumbs currentPath={currentPath} onNavigate={handleNavigate} />

            {error && <p className="mb-4 text-sm text-red-500">Could not refresh the file list: {error}</p>}

            <FileTable
                files={files}
                currentPath={currentPath}
                onDelete={setItemToDelete}
                onDownload={handleDownload}
                onShare={handleShare}
                onVersions={setVersionsFile}
                onPreview={handlePreview}
                onFolderClick={handleNavigate}
            />

            <DeleteConfirmationModal
                isOpen={itemToDelete !== null}
                onClose={() => setItemToDelete(null)}
                onConfirm={handleDelete}
                itemName={itemToDelete?.name}
                isDeleting={isDeleting}
            />

            <CreateFolderModal
                isOpen={isCreateFolderModalOpen}
                onClose={() => setIsCreateFolderModalOpen(false)}
                onCreate={handleCreateFolder}
            />

            <ShareModal isOpen={itemToShare !== null} onClose={() => setItemToShare(null)} item={itemToShare} />

            <PreviewModal
                isOpen={previewItem !== null}
                onClose={() => setPreviewItem(null)}
                item={previewItem}
                onDownload={handleDownload}
            />

            <VersionHistoryModal
                isOpen={versionsFile !== null}
                onClose={() => setVersionsFile(null)}
                file={versionsFile}
                onRestored={() => dispatch(fetchFiles())}
            />
        </div>
    );
};

export default Dashboard;
