import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ShareModal from './ShareModal';
import api from '../services/api';
import { resetDemoInfo } from '../services/demo';
import { formatDate } from '../utils/date';

vi.mock('../services/api');

const addToast = vi.fn();
vi.mock('../hooks/useToast', () => ({
    useToast: () => ({ addToast }),
}));

afterEach(cleanup);

// Some tests replace the clipboard and the legacy copy command; none may leave them replaced.
const originalGlobals = {
    clipboard: Object.getOwnPropertyDescriptor(navigator, 'clipboard'),
    execCommand: Object.getOwnPropertyDescriptor(document, 'execCommand'),
};
const restore = (target, key, descriptor) => {
    if (descriptor) Object.defineProperty(target, key, descriptor);
    else delete target[key];
};
afterEach(() => {
    restore(navigator, 'clipboard', originalGlobals.clipboard);
    restore(document, 'execCommand', originalGlobals.execCommand);
});
afterAll(() => {
    expect(Object.getOwnPropertyDescriptor(navigator, 'clipboard')).toEqual(originalGlobals.clipboard);
    expect(Object.getOwnPropertyDescriptor(document, 'execCommand')).toEqual(originalGlobals.execCommand);
});

const FILE = { name: 'document.pdf', path: 'documents/document.pdf', isDirectory: false };
const SHARE_URL = 'http://localhost/share/12345';
const LINK = { id: 7, createdAt: '2026-10-01T09:00:00Z', expiresAt: '2026-10-02T09:00:00Z', createdBy: 'ada' };
const OTHER_LINK = { id: 8, createdAt: '2026-10-01T10:00:00Z', expiresAt: '2026-10-08T10:00:00Z', createdBy: 'ada' };

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
        resetDemoInfo();
        api.get.mockResolvedValue({ data: [] });
    });

    it('offers only the expirations the public demo allows, defaulting to the longest', async () => {
        const user = userEvent.setup();
        api.get.mockImplementation((url) =>
            Promise.resolve({ data: url === '/api/demo' ? { username: 'demo', maxShareMinutes: 60 } : [] }),
        );
        api.post.mockResolvedValueOnce({ data: { url: SHARE_URL } });
        renderModal();

        await vi.waitFor(() => expect(within(expirationSelect()).getAllByRole('option')).toHaveLength(2));
        expect(expirationSelect()).toHaveValue('60');

        await user.click(generateButton());
        expect(postedParams().get('expirationMinutes')).toBe('60');
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

    it('falls back to selecting the link where the Clipboard API does not exist (plain http)', async () => {
        const user = userEvent.setup();
        api.post.mockResolvedValueOnce({ data: { url: SHARE_URL } });
        Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });
        document.execCommand = vi.fn().mockReturnValue(true);
        renderModal();

        await user.click(generateButton());
        await user.click(await screen.findByTitle(/Copy link/i));

        expect(document.execCommand).toHaveBeenCalledWith('copy');
        expect(screen.getByLabelText('Share link')).toHaveFocus();
        expect(addToast).toHaveBeenCalledWith('Link copied to clipboard', 'success');
    });

    it('tells the user to copy by hand when no copy method works', async () => {
        const user = userEvent.setup();
        api.post.mockResolvedValueOnce({ data: { url: SHARE_URL } });
        Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });
        document.execCommand = vi.fn().mockReturnValue(false);
        renderModal();

        await user.click(generateButton());
        await user.click(await screen.findByTitle(/Copy link/i));

        expect(addToast).toHaveBeenCalledWith(expect.stringMatching(/press Ctrl\+C/), 'info');
    });

    it('warns that a folder link also serves files added later', () => {
        renderModal({ item: { ...FILE, isDirectory: true } });

        expect(screen.getByText(/including files added later/i)).toBeInTheDocument();
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

    it('says a link can be revoked, not that it cannot be withdrawn', () => {
        renderModal();

        expect(screen.getByText(/until it expires or is revoked/i)).toBeInTheDocument();
        expect(screen.queryByText(/cannot be withdrawn/i)).not.toBeInTheDocument();
    });

    it('lists the active links of the item with their expiry', async () => {
        api.get.mockResolvedValueOnce({ data: [LINK, OTHER_LINK] });
        renderModal();

        const list = await screen.findByRole('list', { name: /Active links/i });
        expect(api.get).toHaveBeenCalledWith('/api/share', { params: { path: 'documents/document.pdf' } });
        expect(within(list).getAllByRole('listitem')).toHaveLength(2);
        expect(within(list).getByText(`Expires ${formatDate(LINK.expiresAt)}`)).toBeInTheDocument();
        expect(within(list).getAllByRole('button', { name: /Revoke/i })).toHaveLength(2);
    });

    it('shows no list when the item has no active links', async () => {
        renderModal();

        await vi.waitFor(() => expect(api.get).toHaveBeenCalled());
        expect(screen.queryByRole('list', { name: /Active links/i })).not.toBeInTheDocument();
    });

    it('revokes a link and removes it from the list', async () => {
        const user = userEvent.setup();
        api.get.mockResolvedValueOnce({ data: [LINK, OTHER_LINK] });
        api.delete.mockResolvedValueOnce({ data: { message: 'Share link revoked' } });
        renderModal();

        const list = await screen.findByRole('list', { name: /Active links/i });
        await user.click(within(list).getAllByRole('button', { name: /Revoke/i })[0]);

        expect(api.delete).toHaveBeenCalledWith('/api/share/7');
        expect(addToast).toHaveBeenCalledWith('Link revoked', 'success');
        expect(within(list).getAllByRole('listitem')).toHaveLength(1);
        expect(within(list).queryByText(`Expires ${formatDate(LINK.expiresAt)}`)).not.toBeInTheDocument();
    });

    it('keeps a link listed and reports the error when revoking fails', async () => {
        const user = userEvent.setup();
        api.get.mockResolvedValueOnce({ data: [LINK] });
        api.delete.mockRejectedValueOnce({ response: { data: { message: 'Share link not found' } } });
        renderModal();

        const list = await screen.findByRole('list', { name: /Active links/i });
        await user.click(within(list).getByRole('button', { name: /Revoke/i }));

        expect(await screen.findByText('Share link not found')).toBeInTheDocument();
        expect(within(list).getAllByRole('listitem')).toHaveLength(1);
    });

    it('adds a newly generated link to the list', async () => {
        const user = userEvent.setup();
        api.post.mockResolvedValueOnce({ data: { url: SHARE_URL } });
        renderModal();
        // The active links and the check for the public demo's limits.
        await vi.waitFor(() => expect(api.get).toHaveBeenCalledTimes(2));

        api.get.mockResolvedValueOnce({ data: [LINK] });
        await user.click(generateButton());

        const list = await screen.findByRole('list', { name: /Active links/i });
        expect(api.get.mock.calls.filter(([url]) => url === '/api/share')).toHaveLength(2);
        expect(within(list).getAllByRole('listitem')).toHaveLength(1);
    });

    it('says the new link is shown only once', async () => {
        const user = userEvent.setup();
        api.post.mockResolvedValueOnce({ data: { url: SHARE_URL } });
        renderModal();

        await user.click(generateButton());

        expect(await screen.findByText(/can't be shown again/i)).toBeInTheDocument();
    });

    it('ignores a list that arrives after a different item is shown', async () => {
        let resolveFirst;
        api.get.mockReturnValueOnce(new Promise((resolve) => { resolveFirst = resolve; }));
        const { rerender } = renderModal();

        const otherFile = { name: 'photo.png', path: 'photo.png', isDirectory: false };
        rerender(<ShareModal isOpen onClose={vi.fn()} item={otherFile} />);
        await vi.waitFor(() => expect(api.get).toHaveBeenCalledWith('/api/share', { params: { path: 'photo.png' } }));
        resolveFirst({ data: [LINK] });

        // Let the stale response settle before checking it was dropped.
        await new Promise((resolve) => setTimeout(resolve, 0));
        expect(screen.queryByRole('list', { name: /Active links/i })).not.toBeInTheDocument();
    });

    describe('when the user moves on to another item', () => {
        const A = { name: 'a-secret.pdf', path: 'a-secret.pdf', isDirectory: false };
        const B = { name: 'b-public.pdf', path: 'b-public.pdf', isDirectory: false };
        const A_URL = 'http://localhost/share/token-for-A';

        // As Dashboard renders it: always mounted, with item set back to null on cancel.
        const dialogFor = (item) => <ShareModal isOpen={item !== null} onClose={vi.fn()} item={item} />;

        it('ignores a link that arrives for the previous item', async () => {
            const user = userEvent.setup();
            let resolveA;
            api.post.mockReturnValueOnce(new Promise((resolve) => { resolveA = resolve; }));
            const { rerender } = render(dialogFor(A));

            await user.click(generateButton());
            rerender(dialogFor(null));
            rerender(dialogFor(B));
            await act(async () => resolveA({ data: { url: A_URL } }));

            expect(screen.getByRole('dialog')).toHaveTextContent('Share b-public.pdf');
            expect(screen.queryByDisplayValue(A_URL)).not.toBeInTheDocument();
            expect(generateButton()).toBeEnabled();
        });

        it('ignores an error that arrives for the previous item', async () => {
            const user = userEvent.setup();
            let rejectA;
            api.post.mockReturnValueOnce(new Promise((_resolve, reject) => { rejectA = reject; }));
            const { rerender } = render(dialogFor(A));

            await user.click(generateButton());
            rerender(dialogFor(B));
            await act(async () => rejectA({ response: { data: { message: 'A is gone' } } }));

            expect(screen.queryByText('A is gone')).not.toBeInTheDocument();
        });

        it('does not carry the previous item\'s spinner over', async () => {
            const user = userEvent.setup();
            api.post.mockReturnValueOnce(new Promise(() => {}));
            const { rerender } = render(dialogFor(A));

            await user.click(generateButton());
            rerender(dialogFor(null));
            rerender(dialogFor(B));

            // Nothing has been sent for B yet.
            expect(generateButton()).toBeEnabled();
        });
    });

    it('describes a folder share as a folder', () => {
        render(<ShareModal isOpen onClose={vi.fn()} item={{ name: 'apples', path: 'apples', isDirectory: true }} />);
        expect(screen.getByText(/can download this folder/i)).toBeInTheDocument();
    });
});
