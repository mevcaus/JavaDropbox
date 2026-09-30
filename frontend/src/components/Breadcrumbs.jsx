import { Home, ChevronRight } from 'lucide-react';

const Breadcrumbs = ({ currentPath, onNavigate }) => {
    // currentPath is assumed to be something like "folder1/folder2"
    // We want to link to:
    // Home (Root) -> ""
    // folder1 -> "folder1"
    // folder2 -> "folder1/folder2"

    const parts = currentPath ? currentPath.split('/').filter(Boolean) : [];

    return (
        // The padding leaves room for the focus rings, which the scrolling container would clip.
        <nav
            aria-label="Breadcrumb"
            className="flex items-center text-sm text-gray-500 -mx-1 p-1 mb-3 overflow-x-auto whitespace-nowrap"
        >
            <button
                onClick={() => onNavigate('')}
                aria-current={parts.length === 0 ? 'page' : undefined}
                className="flex items-center rounded-sm hover:text-blue-600 transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2"
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
                        <ChevronRight className="h-4 w-4 mx-2 text-gray-400" aria-hidden="true" />

                        {isLast ? (
                            <span aria-current="page" className="font-semibold text-gray-900">{part}</span>
                        ) : (
                            <button
                                onClick={() => onNavigate(path)}
                                className="rounded-sm hover:text-blue-600 transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2"
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
