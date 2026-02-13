import { Box } from 'lucide-react';

const Logo = ({ collapsed = false, className = "", textClassName = "text-xl font-bold" }) => {
    return (
        <div className={`flex items-center ${className} ${collapsed ? 'justify-center' : ''}`}>
            <div className="relative flex items-center justify-center">
                <div className="bg-blue-600 p-1.5 rounded-lg">
                    <Box className="h-6 w-6 text-white" strokeWidth={2.5} />
                </div>
            </div>

            {!collapsed && (
                <span className={`ml-3 ${textClassName} tracking-tight`}>
                    <span className="text-blue-600">Java</span>
                    <span className="text-slate-800 dark:text-white">DropBox</span>
                </span>
            )}
        </div>
    );
};

export default Logo;
