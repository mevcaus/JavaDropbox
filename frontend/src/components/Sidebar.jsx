import { NavLink } from 'react-router-dom';
import { Folder, HardDrive, ChevronLeft, ChevronRight } from 'lucide-react';
import Logo from './Logo';
import { useSelector } from 'react-redux';
import { selectTotalSize } from '../features/filesSlice';
import { formatSize } from '../utils/format';

const Sidebar = ({ onClose, isCollapsed, toggleCollapse }) => {
    const totalSizeBytes = useSelector(selectTotalSize);

    const navigation = [{ name: 'My Files', href: '/dashboard', icon: Folder }];

    return (
        <div className="h-full flex flex-col bg-slate-900 text-white w-full">
            <div className="flex items-center justify-between h-20 flex-shrink-0 px-4 bg-slate-950 border-b border-slate-800 relative">
                <div className="flex-1 flex items-center justify-center">
                    <Logo collapsed={isCollapsed} variant="white" />
                </div>
                <button
                    onClick={toggleCollapse}
                    aria-label={isCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
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

            {/* There is no quota to measure against, so this reports what is stored and nothing more. */}
            <div className="p-4 bg-slate-950" title={`${formatSize(totalSizeBytes)} stored`}>
                <div className={`flex items-center text-xs text-slate-400 ${isCollapsed ? 'justify-center' : ''}`}>
                    <HardDrive className="h-4 w-4 flex-shrink-0" aria-hidden="true" />
                    {!isCollapsed && <span className="ml-2">{formatSize(totalSizeBytes)} stored</span>}
                </div>
            </div>
        </div>
    );
};

export default Sidebar;
