import { Menu, LogOut, User } from 'lucide-react';
import Logo from './Logo';
import { useDispatch, useSelector } from 'react-redux';
import { logoutUser } from '../features/authSlice';

const Navbar = ({ onMenuClick }) => {
    const dispatch = useDispatch();
    const { user } = useSelector((state) => state.auth);

    const handleLogout = () => {
        dispatch(logoutUser());
    };

    return (
        <header className="bg-white shadow-sm z-10">
            <div className="px-4 sm:px-6 lg:px-8 py-4 flex justify-between items-center">
                <div className="flex items-center">
                    <button
                        type="button"
                        className="lg:hidden p-2 rounded-md text-gray-400 hover:text-gray-500 hover:bg-gray-100 focus:outline-none focus:ring-2 focus:ring-inset focus:ring-indigo-500"
                        onClick={onMenuClick}
                    >
                        <span className="sr-only">Open sidebar</span>
                        <Menu className="h-6 w-6" aria-hidden="true" />
                    </button>
                    <div className="hidden sm:block ml-4 lg:ml-0">
                        <Logo />
                    </div>
                </div>

                <div className="flex items-center">
                    <div className="flex items-center mr-4">
                        <User className="h-5 w-5 text-gray-400 mr-2" />
                        <span className="text-sm font-medium text-gray-700">{user || 'User'}</span>
                    </div>
                    <button
                        onClick={handleLogout}
                        className="inline-flex items-center px-3 py-2 border border-transparent text-sm leading-4 font-medium rounded-md text-red-700 bg-red-100 hover:bg-red-200 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500"
                    >
                        <LogOut className="h-4 w-4 mr-2" />
                        Logout
                    </button>
                </div>
            </div>
        </header>
    );
};

export default Navbar;
