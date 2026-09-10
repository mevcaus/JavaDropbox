import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ShareModal from './ShareModal';
import api from '../services/api';

vi.mock('../services/api');

const addToast = vi.fn();
vi.mock('../hooks/useToast', () => ({
    useToast: () => ({ addToast }),
}));

afterEach(cleanup);

const FILE = { name: 'document.pdf', path: 'documents/document.pdf', isDirectory: false };
const SHARE_URL = 'http://localhost/share/12345';

const renderModal = (props = {}) => {
    const onClose = vi.fn();
    const view = render(<ShareModal isOpen onClose={onClose} item={FILE} {...props} />);
    return { ...view, onClose };
};

const expirationSelect = () => screen.getByLabelText(/Link expires in/i);
const generateButton = () => screen.getByRole('button', { name: /Generate link/i });
const postedParams = () => api.post.mock.calls[0][1];

// userEvent.setup() installs its own clipboard stub, so override it afterwards.
const stubClipboard = () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    return writeText;
};

describe('ShareModal', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        addToast.mockClear();
    });

    it('renders nothing when closed or when no item is selected', () => {
        const { rerender } = render(<ShareModal isOpen={false} onClose={vi.fn()} item={FILE} />);
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

        rerender(<ShareModal isOpen onClose={vi.fn()} item={null} />);
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('defaults to a 24 hour expiration', () => {
        renderModal();
        expect(expirationSelect()).toHaveValue(String(60 * 24));
    });

    it('posts the selected expiration and renders the returned link', async () => {
        const user = userEvent.setup();
        api.post.mockResolvedValueOnce({ data: { url: SHARE_URL } });
        renderModal();

        await user.selectOptions(expirationSelect(), '60');
        await user.click(generateButton());

        expect(api.post).toHaveBeenCalledTimes(1);
        expect(api.post.mock.calls[0][0]).toBe('/api/share');
        expect(postedParams().get('path')).toBe('documents/document.pdf');
        expect(postedParams().get('expirationMinutes')).toBe('60');
        expect(api.post.mock.calls[0][2].headers['Content-Type'])
            .toBe('application/x-www-form-urlencoded');

        expect(await screen.findByDisplayValue(SHARE_URL)).toBeInTheDocument();
        // The form is replaced by the result, so there is nothing left to re-submit
        expect(screen.queryByRole('button', { name: /Generate link/i })).not.toBeInTheDocument();
    });

    it('sends the default expiration when the user does not pick one', async () => {
        const user = userEvent.setup();
        api.post.mockResolvedValueOnce({ data: { url: SHARE_URL } });
        renderModal();

        await user.click(generateButton());

        expect(postedParams().get('expirationMinutes')).toBe('1440');
    });

    it('copies the generated link to the clipboard', async () => {
        const user = userEvent.setup();
        const writeText = stubClipboard();
        api.post.mockResolvedValueOnce({ data: { url: SHARE_URL } });
        renderModal();

        await user.click(generateButton());
        await screen.findByDisplayValue(SHARE_URL);
        await user.click(screen.getByTitle(/Copy link/i));

        expect(writeText).toHaveBeenCalledWith(SHARE_URL);
        expect(addToast).toHaveBeenCalledWith('Link copied to clipboard', 'success');
    });

    it('shows the server error and no link when generation fails', async () => {
        const user = userEvent.setup();
        api.post.mockRejectedValueOnce({ response: { data: { message: 'Path is outside the share root' } } });
        renderModal();

        await user.click(generateButton());

        expect(await screen.findByText('Path is outside the share root')).toBeInTheDocument();
        expect(screen.queryByDisplayValue(SHARE_URL)).not.toBeInTheDocument();
        // The button comes back so the user can retry
        expect(generateButton()).toBeEnabled();
    });

    it('falls back to a generic error when the server sends no message', async () => {
        const user = userEvent.setup();
        api.post.mockRejectedValueOnce(new Error('Network Error'));
        renderModal();

        await user.click(generateButton());

        expect(await screen.findByText('Failed to create share link.')).toBeInTheDocument();
    });

    it('disables the button while the request is in flight so it cannot double-submit', async () => {
        const user = userEvent.setup();
        let resolvePost;
        api.post.mockReturnValueOnce(new Promise((resolve) => { resolvePost = resolve; }));
        renderModal();

        await user.click(generateButton());

        // The label is swapped for a spinner while in flight, so the button loses its name
        expect(screen.queryByRole('button', { name: /Generate link/i })).not.toBeInTheDocument();
        const busy = screen.getAllByRole('button').find((b) => b.disabled);
        expect(busy).toBeDefined();

        await user.click(busy);

        resolvePost({ data: { url: SHARE_URL } });
        expect(await screen.findByDisplayValue(SHARE_URL)).toBeInTheDocument();
        expect(api.post).toHaveBeenCalledTimes(1);
    });

    it('resets when a different item is shared so the previous link is not reused', async () => {
        const user = userEvent.setup();
        api.post.mockResolvedValueOnce({ data: { url: SHARE_URL } });
        const { rerender } = renderModal();

        await user.selectOptions(expirationSelect(), '15');
        await user.click(generateButton());
        await screen.findByDisplayValue(SHARE_URL);

        const otherFile = { name: 'photo.png', path: 'photo.png', isDirectory: false };
        rerender(<ShareModal isOpen onClose={vi.fn()} item={otherFile} />);

        expect(screen.queryByDisplayValue(SHARE_URL)).not.toBeInTheDocument();
        expect(expirationSelect()).toHaveValue(String(60 * 24));
        expect(generateButton()).toBeInTheDocument();
    });

    it('describes a folder share as a folder', () => {
        render(<ShareModal isOpen onClose={vi.fn()} item={{ name: 'apples', path: 'apples', isDirectory: true }} />);
        expect(screen.getByText(/can download this folder/i)).toBeInTheDocument();
    });
});
