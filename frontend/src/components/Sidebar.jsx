import { useEffect, useState } from 'react';
import { NavLink } from 'react-router-dom';
import { Folder, HardDrive, ChevronLeft, ChevronRight, Users } from 'lucide-react';
import Logo from './Logo';
import { useSelector } from 'react-redux';
import { selectTotalSize } from '../features/filesSlice';
import { selectIsAdmin } from '../features/authSlice';
import api from '../services/api';
import { formatSize } from '../utils/format';

export const STORAGE_ENDPOINT = '/api/storage';

// What the signed-in user stores, previous versions included, and their quota: asked again
// whenever the file list changes, which every upload, delete and restore makes it do. Null until
// the server has answered.
const useStorageUsage = () => {
    const files = useSelector((state) => state.files.files);
    const [usage, setUsage] = useState(null);

    useEffect(() => {
        let cancelled = false;
        api.get(STORAGE_ENDPOINT)
            .then((response) => !cancelled && setUsage(response.data))
            .catch(() => {
                // The meter falls back to the size of the file list.
            });
        return () => {
            cancelled = true;
        };
    }, [files]);

    return usage;
};

// Written out in full: Tailwind only generates classes it finds as complete strings in the source.
const barColour = (fraction) => {
    if (fraction >= 0.9) return 'bg-red-500';
    if (fraction >= 0.75) return 'bg-amber-400';
    return 'bg-blue-500';
};

const StorageMeter = ({ isCollapsed }) => {
    const usage = useStorageUsage();
    const treeSize = useSelector(selectTotalSize);
    const used = usage?.usedBytes ?? treeSize;
    const quota = usage?.quotaBytes ?? null;

    // Without a quota there is nothing to measure against, so this reports what is stored.
    const summary = quota ? `${formatSize(used)} of ${formatSize(quota)} used` : `${formatSize(used)} stored`;
    const fraction = quota ? Math.min(used / quota, 1) : 0;

    return (
        <div className="p-4 bg-slate-950" title={summary}>
            <div className={`flex items-center text-xs text-slate-400 ${isCollapsed ? 'justify-center' : ''}`}>
                <HardDrive className="h-4 w-4 flex-shrink-0" aria-hidden="true" />
                {!isCollapsed && <span className="ml-2">{summary}</span>}
            </div>
            {quota && !isCollapsed && (
                <div
                    role="meter"
                    aria-label="Storage used"
                    aria-valuemin={0}
                    aria-valuemax={quota}
                    aria-valuenow={Math.min(used, quota)}
                    aria-valuetext={summary}
                    className="mt-2 h-1.5 w-full rounded-full bg-slate-800 overflow-hidden"
                >
                    <div className={`h-full rounded-full ${barColour(fraction)}`} style={{ width: `${fraction * 100}%` }} />
                </div>
            )}
        </div>
    );
};

const Sidebar = ({ onClose, isCollapsed, toggleCollapse }) => {
    const isAdmin = useSelector(selectIsAdmin);

    const navigation = [
        { name: 'My Files', href: '/dashboard', icon: Folder },
        // Only admins manage the accounts; the server refuses everyone else anyway.
        ...(isAdmin ? [{ name: 'Users', href: '/admin', icon: Users }] : []),
    ];

    return (
        <div className="h-full flex flex-col bg-slate-900 text-white w-full">
            <div className="flex items-center justify-between h-20 flex-shrink-0 px-4 bg-slate-950 border-b border-slate-800 relative">
                <div className="flex-1 flex items-center justify-center">
                    <Logo collapsed={isCollapsed} variant="white" />
                </div>
                <button
                    onClick={toggleCollapse}
                    aria-label={isCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
                    className="absolute -right-3 top-8 bg-slate-800 rounded-full p-1 border border-slate-700 hover:bg-slate-700 text-slate-400 hover:text-white transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-400 focus-visible:ring-offset-2 focus-visible:ring-offset-slate-950 hidden lg:block"
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

            <StorageMeter isCollapsed={isCollapsed} />
        </div>
    );
};

export default Sidebar;
