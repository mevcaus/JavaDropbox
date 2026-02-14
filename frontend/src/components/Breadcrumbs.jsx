import { Home, ChevronRight } from 'lucide-react';

const Breadcrumbs = ({ currentPath, onNavigate }) => {
    // currentPath is assumed to be something like "folder1/folder2"
    // We want to link to:
    // Home (Root) -> ""
    // folder1 -> "folder1"
    // folder2 -> "folder1/folder2"

    const parts = currentPath ? currentPath.split('/').filter(Boolean) : [];

    return (
        <nav className="flex items-center text-sm text-gray-500 mb-4 overflow-x-auto whitespace-nowrap">
            <button
                onClick={() => onNavigate('')}
                className="flex items-center hover:text-blue-600 transition-colors focus:outline-none"
            >
                <Home className="h-4 w-4 mr-1" />
                Home
            </button>

            {parts.map((part, index) => {
                // Reconstruct path up to this part
                const path = parts.slice(0, index + 1).join('/');
                const isLast = index === parts.length - 1;

                return (
                    <div key={path} className="flex items-center">
                        <ChevronRight className="h-4 w-4 mx-2 text-gray-400" />

                        {isLast ? (
                            <span className="font-semibold text-gray-900">{part}</span>
                        ) : (
                            <button
                                onClick={() => onNavigate(path)}
                                className="hover:text-blue-600 transition-colors focus:outline-none"
                            >
                                {part}
                            </button>
                        )}
                    </div>
                );
            })}
        </nav>
    );
};

export default Breadcrumbs;
