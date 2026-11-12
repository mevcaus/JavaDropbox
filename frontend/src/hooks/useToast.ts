import { createContext, useContext } from 'react';

/** How a toast reads: a success, an error, or anything else to know. */
export type ToastType = 'success' | 'error' | 'info';

export interface ToastContextValue {
    /** Shows a message for `duration` milliseconds, `info` unless a type is given. */
    addToast: (message: string, type?: ToastType, duration?: number) => void;
}

export const ToastContext = createContext<ToastContextValue | null>(null);

export const useToast = (): ToastContextValue => {
    const context = useContext(ToastContext);
    if (!context) {
        throw new Error('useToast must be used within a ToastProvider');
    }
    return context;
};
