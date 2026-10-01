import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import Modal from './Modal';

afterEach(cleanup);

const Harness = ({ onClose = vi.fn() }) => {
    const [open, setOpen] = useState(false);
    return (
        <>
            <button onClick={() => setOpen(true)}>Open</button>
            <Modal
                isOpen={open}
                onClose={() => {
                    onClose();
                    setOpen(false);
                }}
                title="Example"
            >
                <input aria-label="First field" />
                <button>Last button</button>
            </Modal>
        </>
    );
};

describe('Modal', () => {
    it('is a labelled modal dialog', async () => {
        const user = userEvent.setup();
        render(<Harness />);
        await user.click(screen.getByText('Open'));

        expect(screen.getByRole('dialog', { name: 'Example' })).toHaveAttribute('aria-modal', 'true');
    });

    it('moves focus into the dialog when it opens', async () => {
        const user = userEvent.setup();
        render(<Harness />);
        await user.click(screen.getByText('Open'));

        // The close button is the first focusable element.
        expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus();
    });

    it('closes on Escape and returns focus to what opened it', async () => {
        const user = userEvent.setup();
        const onClose = vi.fn();
        render(<Harness onClose={onClose} />);
        await user.click(screen.getByText('Open'));

        await user.keyboard('{Escape}');

        expect(onClose).toHaveBeenCalledOnce();
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        expect(screen.getByText('Open')).toHaveFocus();
    });

    it('keeps Tab inside the dialog', async () => {
        const user = userEvent.setup();
        render(<Harness />);
        await user.click(screen.getByText('Open'));

        await user.tab(); // first field
        await user.tab(); // last button
        await user.tab(); // wraps to the close button
        expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus();

        await user.tab({ shift: true });
        expect(screen.getByRole('button', { name: 'Last button' })).toHaveFocus();
    });

    describe('never lets focus leave or get lost', () => {
        const focusIsInDialog = () => screen.getByRole('dialog').contains(document.activeElement);

        const Page = ({ children }) => (
            <>
                <button>Behind the dialog</button>
                {children}
                {/* Toasts sit above the overlay, so a click can move focus there. */}
                <button>Toast close</button>
            </>
        );

        it('pulls focus back in when Tab is pressed while focus is outside', async () => {
            const user = userEvent.setup();
            render(
                <Page>
                    <Modal isOpen onClose={vi.fn()} title="Example">
                        <input aria-label="Field" />
                        <button>Last</button>
                    </Modal>
                </Page>,
            );

            screen.getByRole('button', { name: 'Toast close' }).focus();
            await user.tab();
            expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus();

            screen.getByRole('button', { name: 'Toast close' }).focus();
            await user.tab({ shift: true });
            expect(screen.getByRole('button', { name: 'Last' })).toHaveFocus();
        });

        it('does not reach the page behind with Tab from <body>', async () => {
            const user = userEvent.setup();
            render(
                <Page>
                    <Modal isOpen onClose={vi.fn()} title="Example">
                        <button>Only</button>
                    </Modal>
                </Page>,
            );
            document.activeElement.blur();

            await user.tab();

            expect(focusIsInDialog()).toBe(true);
        });

        it('does not reach the page behind with Shift+Tab from the panel itself', async () => {
            const user = userEvent.setup();
            render(
                <Page>
                    <Modal isOpen onClose={vi.fn()} title="Example">
                        <button>Only</button>
                    </Modal>
                </Page>,
            );
            screen.getByRole('button', { name: 'Close' }).closest('[tabindex="-1"]').focus();

            await user.tab({ shift: true });

            expect(screen.getByRole('button', { name: 'Only' })).toHaveFocus();
        });

        it('keeps focus in the dialog when the focused button is replaced', async () => {
            const Replacing = () => {
                const [done, setDone] = useState(false);
                return (
                    <Modal isOpen onClose={vi.fn()} title="Example">
                        {done ? <p>Link ready</p> : <button onClick={() => setDone(true)}>Generate</button>}
                    </Modal>
                );
            };
            const user = userEvent.setup();
            render(<Page><Replacing /></Page>);

            await user.click(screen.getByRole('button', { name: 'Generate' }));

            expect(screen.getByText('Link ready')).toBeInTheDocument();
            await waitFor(() => expect(focusIsInDialog()).toBe(true));
        });

        it('falls back to the main region when what opened it is gone on close', async () => {
            // e.g. a delete that removes the row, and with it the button that opened the dialog
            const Harness = () => {
                const [open, setOpen] = useState(false);
                const [rowExists, setRowExists] = useState(true);
                return (
                    <main tabIndex={-1}>
                        {rowExists && <button onClick={() => setOpen(true)}>Delete row</button>}
                        <Modal isOpen={open} onClose={() => setOpen(false)} title="Delete">
                            <button onClick={() => { setRowExists(false); setOpen(false); }}>Confirm</button>
                        </Modal>
                    </main>
                );
            };
            const user = userEvent.setup();
            render(<Harness />);

            await user.click(screen.getByRole('button', { name: 'Delete row' }));
            await user.click(screen.getByRole('button', { name: 'Confirm' }));

            expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
            expect(screen.getByRole('main')).toHaveFocus();
        });
    });
});
