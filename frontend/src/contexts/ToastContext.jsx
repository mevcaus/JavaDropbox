import { useState, useCallback, useMemo, useEffect } from 'react';
import { CheckCircle, XCircle, Info, X } from 'lucide-react';
import { ToastContext } from '../hooks/useToast';

// Kept in sync with the duration-300 transition below.
const TRANSITION_MS = 300;

export const ToastProvider = ({ children }) => {
    const [toasts, setToasts] = useState([]);

    const removeToast = useCallback((id) => {
        setToasts((prev) => prev.filter((toast) => toast.id !== id));
    }, []);

    const addToast = useCallback((message, type = 'info', duration = 4000) => {
        const id = Math.random().toString(36).substring(2, 9);
        setToasts((prev) => [...prev, { id, message, type, duration }]);
    }, []);

    const contextValue = useMemo(() => ({ addToast }), [addToast]);

    return (
        <ToastContext.Provider value={contextValue}>
            {children}
            {/*
                The live region is this always-mounted container, not the individual
                toasts: screen readers only announce changes inside a region that was
                already in the DOM, so a region arriving together with its own text
                tends to go unannounced.
            */}
            <div
                role="status"
                aria-live="polite"
                aria-atomic="false"
                className="fixed bottom-4 right-4 z-[100] flex flex-col gap-2"
            >
                {toasts.map((toast) => (
                    <Toast key={toast.id} toast={toast} onRemove={removeToast} />
                ))}
            </div>
        </ToastContext.Provider>
    );
};

const Toast = ({ toast, onRemove }) => {
    const { id, type, message, duration = 4000 } = toast;
    const [isVisible, setIsVisible] = useState(false);
    const [isClosing, setIsClosing] = useState(false);

    // Flip to the visible state on a later frame so the enter transition actually
    // runs. requestAnimationFrame never fires while the tab is hidden, so a timer
    // backs it up; without it a toast raised in a background tab would never start.
    useEffect(() => {
        let innerFrame;
        const outerFrame = requestAnimationFrame(() => {
            innerFrame = requestAnimationFrame(() => setIsVisible(true));
        });
        const fallback = setTimeout(() => setIsVisible(true), 100);
        return () => {
            cancelAnimationFrame(outerFrame);
            if (innerFrame) cancelAnimationFrame(innerFrame);
            clearTimeout(fallback);
        };
    }, []);

    // Count down only once the toast is on screen, so duration is the time it is
    // actually visible rather than the time since it mounted.
    useEffect(() => {
        if (!isVisible) return undefined;
        const timer = setTimeout(() => setIsClosing(true), duration);
        return () => clearTimeout(timer);
    }, [isVisible, duration]);

    // transitionend never fires while nothing is painting, which would strand the
    // toast in the DOM permanently, so drop it on a timer as well.
    useEffect(() => {
        if (!isClosing) return undefined;
        const timer = setTimeout(() => onRemove(id), TRANSITION_MS + 50);
        return () => clearTimeout(timer);
    }, [isClosing, id, onRemove]);

    const handleClose = () => {
        setIsClosing(true);
    };

    const handleTransitionEnd = (event) => {
        // transition-all reports one event per animated property, and the event
        // bubbles up from children, so only act on this element settling.
        if (isClosing && event.target === event.currentTarget) {
            onRemove(id);
        }
    };

    const styles = {
        success: 'bg-green-50 border-green-200 text-green-800',
        error: 'bg-red-50 border-red-200 text-red-800',
        info: 'bg-blue-50 border-blue-200 text-blue-800',
    };

    const icons = {
        success: <CheckCircle className="h-5 w-5 text-green-500" />,
        error: <XCircle className="h-5 w-5 text-red-500" />,
        info: <Info className="h-5 w-5 text-blue-500" />,
    };

    const show = isVisible && !isClosing;

    return (
        <div
            className={`flex items-start gap-3 p-4 border rounded-lg shadow-lg w-80 transform transition-all duration-300 ease-in-out ${
                show ? 'translate-x-0 opacity-100' : 'translate-x-full opacity-0'
            } ${styles[type] || styles.info}`}
            onTransitionEnd={handleTransitionEnd}
        >
            <div className="flex-shrink-0">{icons[type] || icons.info}</div>
            <div className="flex-1 min-w-0">
                <p className="text-sm font-medium">{message}</p>
            </div>
            <button
                onClick={handleClose}
                aria-label="Close notification"
                className="flex-shrink-0 inline-flex text-gray-400 hover:text-gray-500 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500"
            >
                <X className="h-4 w-4" />
            </button>
        </div>
    );
};
