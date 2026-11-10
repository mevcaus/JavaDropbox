import { useEffect, useState } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { useSearchParams } from 'react-router-dom';
import {
    fetchFiles,
    deleteItem,
    selectCurrentFiles,
    createDirectory,
    uploadFiles,
    uploadFolder,
    relativePathOf,
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
import { carriesFiles, readDrop } from '../utils/droppedFiles';
import { Loader2, FolderPlus, FolderUp, Upload as UploadIcon } from 'lucide-react';

// Search results carry their own relativePath; a plain row in this folder may not.
const pathOf = (file, currentPath) =>
    file.relativePath || (currentPath ? `${currentPath}/${file.name}` : file.name);

const notUploaded = (names) =>
    `${names.join(', ')} ${names.length === 1 ? 'was' : 'were'} not uploaded. ${DOT_NAME_RULE}`;

// A label styled as a button around a hidden file input, so clicking it opens the browser's own
// picker. inputProps go to the input, e.g. webkitdirectory to pick a folder.
const UploadButton = ({ icon: Icon, label, busy, disabled, ...inputProps }) => (
    <label
        className={`flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus-within:ring-2 focus-within:ring-offset-2 focus-within:ring-blue-500 shadow-xs transition-colors ${
            disabled ? 'opacity-50 cursor-wait' : 'cursor-pointer'
        }`}
    >
        {busy ? (
            <Loader2 className="h-4 w-4 mr-2 animate-spin" aria-hidden="true" />
        ) : (
            <Icon className="h-4 w-4 mr-2" aria-hidden="true" />
        )}
        {busy ? 'Uploading…' : label}
        <input type="file" className="sr-only" multiple disabled={disabled} {...inputProps} />
    </label>
);

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
    // 'files', 'folder' or 'drop' while an upload is running: one upload at a time.
    const [uploading, setUploading] = useState(null);
    // Whether files are being dragged over the table.
    const [draggingOver, setDraggingOver] = useState(false);

    useEffect(() => {
        dispatch(fetchFiles());
    }, [dispatch]);

    // A file dropped beside the table would otherwise be opened by the browser in place of the
    // app. The table's own handlers run first and claim drops on it.
    useEffect(() => {
        const refuse = (e) => {
            if (e.defaultPrevented || !carriesFiles(e.dataTransfer)) return;
            e.preventDefault();
            e.dataTransfer.dropEffect = 'none';
        };
        window.addEventListener('dragover', refuse);
        window.addEventListener('drop', refuse);
        return () => {
            window.removeEventListener('dragover', refuse);
            window.removeEventListener('drop', refuse);
        };
    }, []);

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
            addToast(notUploaded(skipped.map((file) => file.name)), 'error');
        }
        const selected = chosen.filter((file) => !startsWithDot(file.name));
        if (selected.length === 0) return;

        setUploading('files');
        try {
            await dispatch(uploadFiles({ files: selected, path: currentPath })).unwrap();
            addToast(`Uploaded ${selected.length} ${selected.length === 1 ? 'file' : 'files'} successfully.`, 'success');
        } catch (err) {
            addToast(err, 'error');
        } finally {
            setUploading(null);
        }
    };

    // Uploads { file, relativePath } pairs below the open folder, keeping their subfolders. Used by
    // the folder picker and by a drop. skipped names the dot-named files and folders left out.
    const uploadTree = async (files, skipped, kind) => {
        if (skipped.length > 0) {
            addToast(notUploaded(skipped), 'info');
        }
        if (files.length === 0) return;

        // The folder's name when everything came from one folder. A drop of loose files, or a
        // browser that cannot pick folders and handed over plain files, has none.
        const tops = new Set(files.map(({ relativePath }) => relativePath.split('/')[0]));
        const folderName = tops.size === 1 && files[0].relativePath.includes('/') ? [...tops][0] : null;
        const count = `${files.length} ${files.length === 1 ? 'file' : 'files'}`;
        setUploading(kind);
        try {
            await dispatch(uploadFolder({ files, path: currentPath })).unwrap();
            addToast(
                folderName ? `Uploaded folder "${folderName}" (${count}) successfully.` : `Uploaded ${count} successfully.`,
                'success',
            );
        } catch (err) {
            addToast(err, 'error');
        } finally {
            setUploading(null);
        }
    };

    const handleFolderUpload = async (e) => {
        const input = e.target;
        const chosen = Array.from(input.files ?? []).map((file) => ({ file, relativePath: relativePathOf(file) }));
        input.value = '';

        // A file is left out if it, or a folder it is in, has a dot name. Inside a folder those are
        // mostly put there by the system (.DS_Store, .git), so this is news, not an error, and each
        // name is listed once however many folders it turned up in.
        const dotNameOf = ({ relativePath }) => relativePath.split('/').find(startsWithDot);
        const skipped = [...new Set(chosen.map(dotNameOf).filter(Boolean))];
        await uploadTree(chosen.filter((picked) => !dotNameOf(picked)), skipped, 'folder');
    };

    // Files and folders dragged onto the table. Drags that carry no files (selected text, a link)
    // are left to the browser, so they show no drop state and cannot be dropped.
    const handleDragOver = (e) => {
        if (!carriesFiles(e.dataTransfer)) return;
        e.preventDefault();
        e.dataTransfer.dropEffect = 'copy';
        setDraggingOver(true);
    };

    // Leaving the table for one of its own rows fires dragleave too, so only a move out of it counts.
    // Safari gives dragleave no relatedTarget, so there the pointer's position decides.
    const handleDragLeave = (e) => {
        const zone = e.currentTarget;
        if (e.relatedTarget) {
            if (!zone.contains(e.relatedTarget)) setDraggingOver(false);
            return;
        }
        const { left, right, top, bottom } = zone.getBoundingClientRect();
        if (e.clientX <= left || e.clientX >= right || e.clientY <= top || e.clientY >= bottom) {
            setDraggingOver(false);
        }
    };

    const handleDrop = async (e) => {
        if (!carriesFiles(e.dataTransfer)) return;
        // Without this the browser opens the dropped file in place of the app.
        e.preventDefault();
        setDraggingOver(false);
        if (uploading !== null) {
            addToast('Wait for the current upload to finish, then drop again.', 'info');
            return;
        }
        // Busy from the start: walking a large folder takes a while, and a second drop or upload
        // must not start alongside it.
        setUploading('drop');
        try {
            const dropped = await readDrop(e.dataTransfer);
            await uploadTree(dropped.files, dropped.skipped, 'drop');
        } catch {
            addToast('Could not read the dropped files.', 'error');
        } finally {
            setUploading(null);
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
                <div className="flex flex-wrap gap-3">
                    <UploadButton
                        icon={UploadIcon}
                        label="Upload"
                        busy={uploading === 'files' || uploading === 'drop'}
                        disabled={uploading !== null}
                        onChange={handleFileUpload}
                    />

                    <UploadButton
                        icon={FolderUp}
                        label="Upload folder"
                        busy={uploading === 'folder'}
                        disabled={uploading !== null}
                        onChange={handleFolderUpload}
                        webkitdirectory=""
                    />

                    <button
                        onClick={() => setIsCreateFolderModalOpen(true)}
                        className="flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-green-600 hover:bg-green-700 focus:outline-hidden focus:ring-2 focus:ring-offset-2 focus:ring-green-500 shadow-xs transition-colors"
                    >
                        <FolderPlus className="h-4 w-4 mr-2" aria-hidden="true" />
                        New Folder
                    </button>
                </div>
            </div>

            <Breadcrumbs currentPath={currentPath} onNavigate={handleNavigate} />

            {error && <p className="mb-4 text-sm text-red-500">Could not refresh the file list: {error}</p>}

            {/* min-h keeps an empty folder a target worth aiming at. */}
            <div
                className="relative min-h-64"
                onDragEnter={handleDragOver}
                onDragOver={handleDragOver}
                onDragLeave={handleDragLeave}
                onDrop={handleDrop}
            >
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
                {draggingOver && (
                    // pointer-events-none so the rows below stay the drag's target and dragleave
                    // keeps working.
                    <div className="pointer-events-none absolute inset-0 z-10 flex items-center justify-center rounded-lg border-2 border-dashed border-blue-500 bg-blue-50/90">
                        <p className="flex items-center text-sm font-medium text-blue-700">
                            <UploadIcon className="h-5 w-5 mr-2" aria-hidden="true" />
                            {uploading !== null
                                ? 'Wait for the current upload to finish'
                                : `Drop to upload to ${currentPath ? `"${currentPath.split('/').at(-1)}"` : 'My Files'}`}
                        </p>
                    </div>
                )}
            </div>

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
