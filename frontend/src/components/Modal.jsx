import { useEffect, useId, useRef } from 'react';
import { X } from 'lucide-react';

const FOCUSABLE =
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * The dialog shell every modal shares. It behaves the way aria-modal promises: Escape closes it,
 * Tab stays inside it, focus moves in when it opens and back to where it was when it closes.
 */
const Modal = ({ isOpen, onClose, title, icon, iconClassName = '', initialFocusRef, children }) => {
    const titleId = useId();
    const panelRef = useRef(null);

    // The latest onClose, without re-running the focus effect every time the parent re-renders.
    const onCloseRef = useRef(onClose);
    useEffect(() => {
        onCloseRef.current = onClose;
    });

    useEffect(() => {
        if (!isOpen) return undefined;

        const previouslyFocused = document.activeElement;
        const panel = panelRef.current;
        (initialFocusRef?.current ?? panel.querySelector(FOCUSABLE) ?? panel).focus();

        const onKeyDown = (event) => {
            if (event.key === 'Escape') {
                event.stopPropagation();
                onCloseRef.current();
                return;
            }
            if (event.key !== 'Tab') return;

            const focusable = [...panel.querySelectorAll(FOCUSABLE)];
            if (focusable.length === 0) {
                event.preventDefault();
                return;
            }
            const first = focusable[0];
            const last = focusable[focusable.length - 1];
            if (event.shiftKey && document.activeElement === first) {
                event.preventDefault();
                last.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault();
                first.focus();
            }
        };

        document.addEventListener('keydown', onKeyDown);
        return () => {
            document.removeEventListener('keydown', onKeyDown);
            previouslyFocused?.focus?.();
        };
    }, [isOpen, initialFocusRef]);

    if (!isOpen) return null;

    return (
        <div className="fixed inset-0 z-50 overflow-y-auto" role="dialog" aria-modal="true" aria-labelledby={titleId}>
            <div className="flex items-end justify-center min-h-screen pt-4 px-4 pb-20 text-center sm:block sm:p-0">
                <div className="fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity" aria-hidden="true" onClick={onClose} />

                <span className="hidden sm:inline-block sm:align-middle sm:h-screen" aria-hidden="true">&#8203;</span>

                <div
                    ref={panelRef}
                    tabIndex={-1}
                    className="relative inline-block align-bottom bg-white rounded-lg px-4 pt-5 pb-4 text-left overflow-hidden shadow-xl transform transition-all sm:my-8 sm:align-middle sm:max-w-lg sm:w-full sm:p-6 focus:outline-none"
                >
                    <div className="absolute top-0 right-0 pt-4 pr-4">
                        <button
                            type="button"
                            className="bg-white rounded-md text-gray-400 hover:text-gray-500 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500"
                            onClick={onClose}
                        >
                            <span className="sr-only">Close</span>
                            <X className="h-6 w-6" aria-hidden="true" />
                        </button>
                    </div>

                    <div className="sm:flex sm:items-start">
                        {icon && (
                            <div className={`mx-auto flex-shrink-0 flex items-center justify-center h-12 w-12 rounded-full sm:mx-0 sm:h-10 sm:w-10 ${iconClassName}`}>
                                {icon}
                            </div>
                        )}
                        <div className="mt-3 text-center sm:mt-0 sm:ml-4 sm:text-left w-full min-w-0">
                            <h3 id={titleId} className="text-lg leading-6 font-medium text-gray-900 pr-8 break-words">
                                {title}
                            </h3>
                            {children}
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
};

/** The row of buttons at the bottom of a modal, primary action first. */
export const ModalActions = ({ children }) => (
    <div className="mt-5 sm:mt-4 sm:flex sm:flex-row-reverse sm:gap-3">{children}</div>
);

export default Modal;
