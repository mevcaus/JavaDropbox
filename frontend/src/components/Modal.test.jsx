import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
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
});
