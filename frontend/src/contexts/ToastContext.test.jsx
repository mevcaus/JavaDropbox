import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { ToastProvider } from './ToastContext';
import { useToast } from '../hooks/useToast';

const Raise = ({ type, message = 'Upload failed' }) => {
    const { addToast } = useToast();
    return <button onClick={() => addToast(message, type)}>raise {type}</button>;
};

const renderToasts = () =>
    render(
        <ToastProvider>
            <Raise type="error" />
            <Raise type="success" message="Uploaded" />
        </ToastProvider>,
    );

// Advance in steps so React can commit and start the next timer between them.
const advance = (ms) => {
    for (let elapsed = 0; elapsed < ms; elapsed += 250) {
        act(() => vi.advanceTimersByTime(250));
    }
};

describe('ToastProvider', () => {
    beforeEach(() => vi.useFakeTimers());

    afterEach(() => {
        cleanup();
        vi.useRealTimers();
    });

    it('announces an error assertively', () => {
        renderToasts();
        fireEvent.click(screen.getByText('raise error'));

        expect(screen.getByText('Upload failed').closest('[role="alert"]')).not.toBeNull();
    });

    it('announces a success politely', () => {
        renderToasts();
        fireEvent.click(screen.getByText('raise success'));

        expect(screen.getByText('Uploaded').closest('[role="status"]')).not.toBeNull();
        expect(screen.getByText('Uploaded').closest('[role="alert"]')).toBeNull();
    });

    it('dismisses a toast nobody touches after its duration', () => {
        renderToasts();
        fireEvent.click(screen.getByText('raise error'));

        advance(5_000);

        expect(screen.queryByText('Upload failed')).not.toBeInTheDocument();
    });

    it('keeps a toast while the pointer is over it, and dismisses it after the pointer leaves', () => {
        renderToasts();
        fireEvent.click(screen.getByText('raise error'));
        advance(250);

        fireEvent.mouseEnter(screen.getByText('Upload failed'));
        advance(10_000);
        expect(screen.getByText('Upload failed')).toBeInTheDocument();

        fireEvent.mouseLeave(screen.getByText('Upload failed'));
        advance(5_000);
        expect(screen.queryByText('Upload failed')).not.toBeInTheDocument();
    });

    it('keeps a toast while its close button has focus, and dismisses it after focus leaves', () => {
        renderToasts();
        fireEvent.click(screen.getByText('raise error'));
        advance(250);
        const close = screen.getByRole('button', { name: 'Close notification' });

        act(() => close.focus());
        advance(10_000);
        expect(screen.getByText('Upload failed')).toBeInTheDocument();

        act(() => close.blur());
        advance(5_000);
        expect(screen.queryByText('Upload failed')).not.toBeInTheDocument();
    });

    it('still closes at once from the close button', () => {
        renderToasts();
        fireEvent.click(screen.getByText('raise error'));
        advance(250);

        fireEvent.click(screen.getByRole('button', { name: 'Close notification' }));
        advance(500);

        expect(screen.queryByText('Upload failed')).not.toBeInTheDocument();
    });
});
