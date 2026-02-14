import { useCallback } from 'react';
import { useDropzone } from 'react-dropzone';
import { X, UploadCloud } from 'lucide-react';
import { useDispatch } from 'react-redux';
import { uploadFiles } from '../features/filesSlice';

const UploadModal = ({ isOpen, onClose, currentPath = "" }) => {
    const dispatch = useDispatch();

    const onDrop = useCallback((acceptedFiles) => {
        if (acceptedFiles.length > 0) {
            dispatch(uploadFiles({ files: acceptedFiles, path: currentPath }));
            onClose();
        }
    }, [dispatch, currentPath, onClose]);

    const { getRootProps, getInputProps, isDragActive } = useDropzone({ onDrop });

    const handleKeyDown = useCallback((e) => {
        if (e.key === 'Escape') {
            onClose();
        }
    }, [onClose]);

    if (!isOpen) return null;

    return (
        <div
            className="fixed inset-0 z-50 overflow-y-auto"
            aria-labelledby="modal-title"
            role="dialog"
            aria-modal="true"
            onKeyDown={handleKeyDown}
        >
            <div className="flex items-end justify-center min-h-screen pt-4 px-4 pb-20 text-center sm:block sm:p-0">
                <div
                    className="fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity"
                    aria-hidden="true"
                    onClick={onClose}
                />

                <span className="hidden sm:inline-block sm:align-middle sm:h-screen" aria-hidden="true">&#8203;</span>

                <div className="inline-block align-bottom bg-white rounded-lg px-4 pt-5 pb-4 text-left overflow-hidden shadow-xl transform transition-all sm:my-8 sm:align-middle sm:max-w-lg sm:w-full sm:p-6">
                    <div className="absolute top-0 right-0 pt-4 pr-4">
                        <button
                            type="button"
                            className="bg-white rounded-md text-gray-400 hover:text-gray-500 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500"
                            onClick={onClose}
                        >
                            <span className="sr-only">Close</span>
                            <X className="h-6 w-6" aria-hidden="true" />
                        </button>
                    </div>

                    <div>
                        <div className="mx-auto flex items-center justify-center h-12 w-12 rounded-full bg-blue-100">
                            <UploadCloud className="h-6 w-6 text-blue-600" aria-hidden="true" />
                        </div>
                        <div className="mt-3 text-center sm:mt-5">
                            <h3 className="text-lg leading-6 font-medium text-gray-900" id="modal-title">
                                Upload Files
                            </h3>
                            <div className="mt-2">
                                <p className="text-sm text-gray-500">
                                    Drag and drop files here, or click to select files.
                                </p>
                            </div>
                        </div>
                    </div>

                    <div className="mt-5 sm:mt-6">
                        <div
                            {...getRootProps()}
                            className={`
                                mt-1 flex justify-center px-6 pt-5 pb-6 
                                border-2 border-gray-300 border-dashed rounded-md 
                                cursor-pointer hover:border-blue-500 transition-colors
                                ${isDragActive ? 'border-blue-500 bg-blue-50' : ''}
                            `}
                        >
                            <div className="space-y-1 text-center">
                                <UploadCloud className="mx-auto h-12 w-12 text-gray-400" />
                                <div className="flex text-sm text-gray-600">
                                    <label
                                        htmlFor="file-upload"
                                        className="relative cursor-pointer bg-white rounded-md font-medium text-blue-600 hover:text-blue-500 focus-within:outline-none focus-within:ring-2 focus-within:ring-offset-2 focus-within:ring-blue-500"
                                    >
                                        <span>Upload a file</span>
                                        <input {...getInputProps()} />
                                    </label>
                                    <p className="pl-1">or drag and drop</p>
                                </div>
                                <p className="text-xs text-gray-500">Any file up to 10MB</p>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default UploadModal;
