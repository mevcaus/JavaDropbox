import { useEffect, useState } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { fetchFiles, deleteItem, setCurrentPath, selectCurrentFiles, createDirectory, uploadFiles } from '../features/filesSlice';
import FileTable from '../components/FileTable';
import Breadcrumbs from '../components/Breadcrumbs';
import DeleteConfirmationModal from '../components/DeleteConfirmationModal';
import CreateFolderModal from '../components/CreateFolderModal';
import InfoModal from '../components/InfoModal';
import { Loader2, FolderPlus, ChevronDown, Upload as UploadIcon, HardDrive as HardDriveIcon, File as FileIcon } from 'lucide-react';
import api from '../services/api';

const Dashboard = () => {
    const dispatch = useDispatch();
    const files = useSelector(selectCurrentFiles);
    const { loading, error, currentPath } = useSelector((state) => state.files);

    // State for deletion modal
    const [isDeleteModalOpen, setIsDeleteModalOpen] = useState(false);
    const [itemToDelete, setItemToDelete] = useState(null);
    const [isCreateFolderModalOpen, setIsCreateFolderModalOpen] = useState(false);
    const [isUploadDropdownOpen, setIsUploadDropdownOpen] = useState(false);
    // State for info modal
    const [infoModalState, setInfoModalState] = useState({ isOpen: false, title: '', message: '' });

    useEffect(() => {
        dispatch(fetchFiles());
    }, [dispatch]);

    const handleNavigate = (path) => {
        dispatch(setCurrentPath(path));
    };

    const handleFolderClick = (folderName) => {
        const newPath = currentPath ? `${currentPath}/${folderName}` : folderName;
        dispatch(setCurrentPath(newPath));
    };

    const handleExecuteCreateFolder = async (folderName) => {
        if (folderName) {
            await dispatch(createDirectory({ path: currentPath, name: folderName }));
        }
    };

    const handleFileUpload = (e) => {
        if (e.target.files && e.target.files.length > 0) {
            dispatch(uploadFiles({ files: e.target.files, path: currentPath }));
        }
        setIsUploadDropdownOpen(false);
    };

    // Open modal
    const confirmDelete = (file) => {
        setItemToDelete(file);
        setIsDeleteModalOpen(true);
    };

    const handleExecuteDelete = async () => {
        if (itemToDelete) {
            const pathToDelete = itemToDelete.relativePath || (currentPath ? `${currentPath}/${itemToDelete.name}` : itemToDelete.name);
            await dispatch(deleteItem(pathToDelete));
            setIsDeleteModalOpen(false);
            setItemToDelete(null);
        }
    };

    const handleDownload = async (file) => {
        try {
            const path = file.relativePath || (currentPath ? `${currentPath}/${file.name}` : file.name);
            const response = await api.get(`/api/download?path=${encodeURIComponent(path)}`, {
                responseType: 'blob',
            });

            const blob = new Blob([response.data], { type: response.headers['content-type'] });
            const url = window.URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = url;

            let filename = file.name;
            if (file.isDirectory) {
                filename += '.zip';
            }

            link.setAttribute('download', filename);
            document.body.appendChild(link);
            link.click();
            link.remove();
        } catch (error) {
            console.error('Download failed', error);
            // alert('Download failed');
            setInfoModalState({ isOpen: true, title: 'Error', message: 'Download failed. Please try again.' });
        }
    };

    const showFeatureNotImplemented = (featureName) => {
        setInfoModalState({
            isOpen: true,
            title: 'Coming Soon',
            message: `${featureName} is not implemented yet. Stay tuned for updates!`
        });
        setIsUploadDropdownOpen(false);
    };

    const closeInfoModal = () => {
        setInfoModalState((prev) => ({ ...prev, isOpen: false }));
    };

    if (loading) {
        return (
            <div className="flex items-center justify-center h-64">
                <Loader2 className="h-8 w-8 animate-spin text-blue-500" />
            </div>
        );
    }

    if (error) {
        return <div className="text-red-500 text-center py-4">Error loading files: {error.message || JSON.stringify(error)}</div>;
    }

    return (
        <div>
            <div className="mb-6 flex items-center justify-between">
                <h1 className="text-2xl font-semibold text-gray-900">My Files</h1>
                <div className="flex space-x-3">
                    {/* Upload Dropdown */}
                    <div className="relative">
                        <button
                            onClick={() => setIsUploadDropdownOpen(!isUploadDropdownOpen)}
                            className="flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 shadow-sm transition-colors"
                        >
                            <UploadIcon className="h-4 w-4 mr-2" />
                            Upload
                            <ChevronDown className="h-4 w-4 ml-2" />
                        </button>

                        {isUploadDropdownOpen && (
                            <div className="absolute right-0 mt-2 w-56 rounded-md shadow-lg bg-white ring-1 ring-black ring-opacity-5 z-10">
                                <div className="py-1" role="menu" aria-orientation="vertical" aria-labelledby="options-menu">
                                    <label className="flex items-center px-4 py-2 text-sm text-gray-700 hover:bg-gray-100 hover:text-gray-900 cursor-pointer" role="menuitem">
                                        <FileIcon className="h-4 w-4 mr-2 text-gray-500" />
                                        <span className="ml-2">File</span>
                                        <input type="file" className="hidden" multiple onChange={handleFileUpload} />
                                    </label>
                                    <button
                                        className="w-full flex items-center px-4 py-2 text-sm text-gray-700 hover:bg-gray-100 hover:text-gray-900 text-left"
                                        role="menuitem"
                                        onClick={() => showFeatureNotImplemented("Folder upload")}
                                    >
                                        <FolderPlus className="h-4 w-4 mr-2 text-blue-500" />
                                        Folder
                                    </button>
                                    <button
                                        className="w-full flex items-center px-4 py-2 text-sm text-gray-700 hover:bg-gray-100 hover:text-gray-900 text-left"
                                        role="menuitem"
                                        onClick={() => showFeatureNotImplemented("Google Drive import")}
                                    >
                                        <span className="mr-2 font-bold text-green-600">G</span> Google Drive
                                    </button>
                                    <button
                                        className="w-full flex items-center px-4 py-2 text-sm text-gray-700 hover:bg-gray-100 hover:text-gray-900 text-left"
                                        role="menuitem"
                                        onClick={() => showFeatureNotImplemented("OneDrive import")}
                                    >
                                        <span className="mr-2 font-bold text-blue-600">O</span> OneDrive
                                    </button>
                                </div>
                            </div>
                        )}
                    </div>

                    <button
                        onClick={() => setIsCreateFolderModalOpen(true)}
                        className="flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-green-600 hover:bg-green-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-green-500 shadow-sm transition-colors"
                    >
                        <FolderPlus className="h-4 w-4 mr-2" />
                        New Folder
                    </button>

                    <button
                        className="flex items-center px-4 py-2 border border-gray-300 text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500 shadow-sm transition-colors"
                        onClick={() => showFeatureNotImplemented("Desktop App installer")}
                    >
                        <HardDriveIcon className="h-4 w-4 mr-2" />
                        Install App
                    </button>
                </div>
            </div>

            <Breadcrumbs currentPath={currentPath} onNavigate={handleNavigate} />

            <FileTable
                files={files}
                onDelete={confirmDelete}
                onDownload={handleDownload}
                onFolderClick={handleFolderClick}
            />

            <DeleteConfirmationModal
                isOpen={isDeleteModalOpen}
                onClose={() => setIsDeleteModalOpen(false)}
                onConfirm={handleExecuteDelete}
                itemName={itemToDelete?.name}
            />

            <InfoModal
                isOpen={infoModalState.isOpen}
                onClose={closeInfoModal}
                title={infoModalState.title}
                message={infoModalState.message}
            />

            <CreateFolderModal
                isOpen={isCreateFolderModalOpen}
                onClose={() => setIsCreateFolderModalOpen(false)}
                onCreate={handleExecuteCreateFolder}
            />
        </div>
    );
};

export default Dashboard;
