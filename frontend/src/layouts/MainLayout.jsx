import { useEffect, useState } from 'react';
import { Outlet, Navigate, useLocation } from 'react-router-dom';
import { useSelector, useDispatch } from 'react-redux';
import Navbar from '../components/Navbar';
import Sidebar from '../components/Sidebar';
import UploadModal from '../components/UploadModal';
import { Menu } from 'lucide-react';

const MainLayout = () => {
    const { isAuthenticated } = useSelector((state) => state.auth);
    // Get currentPath to pass to UploadModal so uploads go to the right folder
    const { currentPath } = useSelector((state) => state.files);

    const location = useLocation();
    const [isSidebarOpen, setIsSidebarOpen] = useState(false);
    const [isSidebarCollapsed, setIsSidebarCollapsed] = useState(false); // Desktop collapse state
    const [isUploadModalOpen, setIsUploadModalOpen] = useState(false);

    if (!isAuthenticated) {
        return <Navigate to="/login" state={{ from: location }} replace />;
    }

    return (
        <div className="flex h-screen bg-gray-100 overflow-hidden">
            {/* Mobile Sidebar Overlay */}
            {isSidebarOpen && (
                <div
                    className="fixed inset-0 bg-black bg-opacity-50 z-20 lg:hidden"
                    onClick={() => setIsSidebarOpen(false)}
                />
            )}

            {/* Sidebar */}
            <div className={`fixed inset-y-0 left-0 z-30 transition-all duration-300 ease-in-out bg-slate-900 shadow-lg 
                ${isSidebarCollapsed ? 'w-20' : 'w-64'} 
                lg:static lg:inset-0 
                ${isSidebarOpen ? 'translate-x-0' : '-translate-x-full lg:translate-x-0'}`
            }>
                <Sidebar
                    onClose={() => setIsSidebarOpen(false)}
                    onUploadClick={() => {
                        setIsSidebarOpen(false); // Close sidebar on mobile
                        setIsUploadModalOpen(true);
                    }}
                    isCollapsed={isSidebarCollapsed}
                    toggleCollapse={() => setIsSidebarCollapsed(!isSidebarCollapsed)}
                />
            </div>

            {/* Main Content */}
            <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
                <Navbar onMenuClick={() => setIsSidebarOpen(true)} />
                <main className="flex-1 overflow-auto p-4 sm:p-6 lg:p-8">
                    <Outlet />
                </main>
            </div>

            <UploadModal
                isOpen={isUploadModalOpen}
                onClose={() => setIsUploadModalOpen(false)}
                currentPath={currentPath}
            />
        </div>
    );
};

export default MainLayout;
