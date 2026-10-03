import { useEffect, useId, useRef } from 'react';
import { X } from 'lucide-react';

const FOCUSABLE =
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

// Where focus goes on close when the element that opened the dialog is gone (a deleted row's
// button, say). MainLayout makes its <main> focusable for this.
const FALLBACK_FOCUS = 'main[tabindex]';

// Whether focus has nowhere sensible to be: on <body>, or on an element that was just disabled or
// removed (browsers differ in when they move focus off those, so check for them directly).
const isFocusLost = (active) => !active || active === document.body || active.disabled || !active.isConnected;

// Written out in full: Tailwind only generates classes it finds as complete strings in the source.
const WIDTHS = {
    default: 'sm:max-w-lg',
    wide: 'sm:max-w-4xl',
};

/**
 * The dialog shell every modal shares. It behaves the way aria-modal promises: Escape closes it,
 * Tab stays inside it, focus moves in when it opens and back to where it was when it closes.
 */
const Modal = ({ isOpen, onClose, title, icon, iconClassName = '', initialFocusRef, size = 'default', children }) => {
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
                panel.focus();
                return;
            }
            const first = focusable[0];
            const last = focusable[focusable.length - 1];
            const active = document.activeElement;
            // Focus can be outside (a toast above the overlay was clicked), on <body> (the focused
            // control went away) or on the panel itself; the browser's own Tab would then step to
            // the page behind, so bring it back in at the matching end.
            if (active === panel || !panel.contains(active)) {
                event.preventDefault();
                (event.shiftKey ? last : first).focus();
            } else if (event.shiftKey && active === first) {
                event.preventDefault();
                last.focus();
            } else if (!event.shiftKey && active === last) {
                event.preventDefault();
                first.focus();
            }
        };

        // A focused button that is disabled or replaced (Generate turning into the link) drops
        // focus to <body>; keep it in the dialog instead.
        const observer = new MutationObserver(() => {
            if (isFocusLost(document.activeElement)) panel.focus();
        });
        observer.observe(panel, { childList: true, subtree: true, attributes: true, attributeFilter: ['disabled'] });

        document.addEventListener('keydown', onKeyDown);
        return () => {
            document.removeEventListener('keydown', onKeyDown);
            observer.disconnect();
            const target = isFocusLost(previouslyFocused) ? document.querySelector(FALLBACK_FOCUS) : previouslyFocused;
            target?.focus?.();
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
                    className={`relative inline-block align-bottom bg-white rounded-lg px-4 pt-5 pb-4 text-left overflow-hidden shadow-xl transform transition-all sm:my-8 sm:align-middle ${WIDTHS[size]} sm:w-full sm:p-6 focus:outline-none`}
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
