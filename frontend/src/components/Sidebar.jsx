import { NavLink } from 'react-router-dom';
import { Folder, Clock, ChevronLeft, ChevronRight } from 'lucide-react';
import Logo from './Logo';
import { useSelector } from 'react-redux';
import { selectTotalSize } from '../features/filesSlice';

const Sidebar = ({ onClose, onUploadClick, isCollapsed, toggleCollapse }) => {
    const totalSizeBytes = useSelector(selectTotalSize);

    // Constant quota for now (5GB)
    const QUOTA_BYTES = 5 * 1024 * 1024 * 1024; // 5 GB
    const usedPercentage = Math.min((totalSizeBytes / QUOTA_BYTES) * 100, 100);

    const formatSize = (bytes) => {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    };

    const navigation = [
        { name: 'My Files', href: '/dashboard', icon: Folder },
        { name: 'Recent', href: '/recent', icon: Clock },
    ];

    return (
        <div className="h-full flex flex-col bg-slate-900 text-white w-full">
            <div className="flex items-center justify-between h-20 flex-shrink-0 px-4 bg-slate-950 border-b border-slate-800 relative">
                <div className="flex-1 flex items-center justify-center">
                    <Logo collapsed={isCollapsed} textClassName="text-xl font-bold text-white" />
                </div>
                <button
                    onClick={toggleCollapse}
                    className="absolute -right-3 top-8 bg-slate-800 rounded-full p-1 border border-slate-700 hover:bg-slate-700 text-slate-400 hover:text-white transition-colors focus:outline-none hidden lg:block"
                >
                    {isCollapsed ? <ChevronRight className="h-4 w-4" /> : <ChevronLeft className="h-4 w-4" />}
                </button>
            </div>

            <div className="flex-1 flex flex-col overflow-y-auto px-2 py-4 space-y-2">

                <nav className="space-y-1">
                    {navigation.map((item) => (
                        <NavLink
                            key={item.name}
                            to={item.href}
                            onClick={onClose}
                            className={({ isActive }) =>
                                `group flex items-center px-4 py-2 text-sm font-medium rounded-md transition-colors ${isActive
                                    ? 'bg-slate-800 text-white'
                                    : 'text-slate-300 hover:bg-slate-800 hover:text-white'
                                } ${isCollapsed ? 'justify-center' : ''}`
                            }
                            title={isCollapsed ? item.name : ''}
                        >
                            <item.icon
                                className={`${isCollapsed ? 'mr-0' : 'mr-3'} h-5 w-5 flex-shrink-0 text-slate-400 group-hover:text-white transition-colors`}
                                aria-hidden="true"
                            />
                            {!isCollapsed && item.name}
                        </NavLink>
                    ))}
                </nav>
            </div>

            <div className="p-4 bg-slate-950">
                {!isCollapsed ? (
                    <>
                        <div className="w-full bg-slate-800 rounded-full h-2.5 dark:bg-slate-700">
                            <div className="bg-blue-600 h-2.5 rounded-full" style={{ width: `${usedPercentage}%` }}></div>
                        </div>
                        <p className="mt-2 text-xs text-slate-400">{formatSize(totalSizeBytes)} of 5 GB used</p>
                    </>
                ) : (
                    /* Small compact usage indicator or just hide it */
                    <div className="flex flex-col items-center">
                        <div className="w-2 bg-slate-800 rounded-full h-10 dark:bg-slate-700 relative overflow-hidden">
                            <div className="bg-blue-600 w-full absolute bottom-0 rounded-full" style={{ height: `${usedPercentage}%` }}></div>
                        </div>
                    </div>
                )}
            </div>
        </div>
    );
};

export default Sidebar;
