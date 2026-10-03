import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import PreviewModal from './PreviewModal';
import api from '../services/api';
import { controlsWithoutFocusIndicator } from '../test/focusIndicator';

vi.mock('../services/api');

const item = (overrides) => ({ name: 'notes.txt', path: 'docs/notes.txt', previewType: 'text', size: 18, ...overrides });

const renderModal = (props = {}) => {
    const handlers = { onClose: vi.fn(), onDownload: vi.fn(), ...props };
    const result = render(<PreviewModal isOpen item={item()} {...handlers} />);
    return { ...handlers, ...result };
};

describe('PreviewModal', () => {
    afterEach(() => {
        cleanup();
        vi.resetAllMocks();
    });

    it('shows an image through the preview endpoint, with its path encoded', () => {
        renderModal({ item: item({ name: 'a & b.png', path: 'Photos/a & b.png', previewType: 'image' }) });

        const image = screen.getByRole('img', { name: 'a & b.png' });
        expect(image).toHaveAttribute('src', '/api/files/preview?path=Photos%2Fa%20%26%20b.png');
        expect(api.get).not.toHaveBeenCalled();
    });

    it('says so when an image fails to load', () => {
        renderModal({ item: item({ name: 'broken.png', previewType: 'image' }) });

        fireEvent.error(screen.getByRole('img', { name: 'broken.png' }));

        expect(screen.getByText('Could not load the image.')).toBeInTheDocument();
    });

    it('frames a PDF from the preview endpoint', () => {
        renderModal({ item: item({ name: 'report.pdf', path: 'report.pdf', previewType: 'pdf' }) });

        expect(screen.getByTitle('Preview of report.pdf')).toHaveAttribute('src', '/api/files/preview?path=report.pdf');
    });

    it('fetches only the start of a text file, as text, and shows it', async () => {
        api.get.mockResolvedValueOnce({ status: 206, headers: { 'content-range': 'bytes 0-17/18' }, data: '{"a": 1}\n<b>bold</b>\n' });
        renderModal();

        const contents = await screen.findByLabelText('Contents of notes.txt');
        // Shown as written: JSON is not parsed, markup is not rendered.
        expect(contents.textContent).toBe('{"a": 1}\n<b>bold</b>\n');
        expect(contents.querySelector('b')).toBeNull();
        expect(api.get).toHaveBeenCalledWith('/api/files/preview?path=docs%2Fnotes.txt', {
            headers: { Range: 'bytes=0-262143' },
            responseType: 'text',
            signal: expect.any(AbortSignal),
        });
        expect(screen.queryByText(/Showing the first/)).not.toBeInTheDocument();
    });

    it('says when a large text file is cut short, and ends on a whole line', async () => {
        api.get.mockResolvedValueOnce({
            status: 206,
            headers: { 'content-range': 'bytes 0-262143/1048576' },
            data: 'first line\nsecond line\nhalf a li',
        });
        renderModal({ item: item({ size: 1048576 }) });

        const contents = await screen.findByLabelText('Contents of notes.txt');
        expect(contents.textContent).toBe('first line\nsecond line');
        expect(screen.getByText(/Showing the first 256 KB of 1 MB/)).toBeInTheDocument();
    });

    it('treats an unsatisfiable range as an empty file', async () => {
        api.get.mockRejectedValueOnce({ response: { status: 416, data: '' } });
        renderModal({ item: item({ size: 0 }) });

        expect(await screen.findByText('This file is empty.')).toBeInTheDocument();
    });

    it("shows the server's message when the preview fails", async () => {
        api.get.mockRejectedValueOnce({
            response: { status: 400, data: '{"message":"This kind of file cannot be previewed: notes.txt"}' },
        });
        renderModal();

        expect(await screen.findByText('This kind of file cannot be previewed: notes.txt')).toBeInTheDocument();
    });

    it('offers the download and closes', async () => {
        api.get.mockResolvedValueOnce({ status: 200, headers: {}, data: 'hello' });
        const user = userEvent.setup();
        const { onDownload, onClose } = renderModal();
        await screen.findByLabelText('Contents of notes.txt');

        await user.click(screen.getByRole('button', { name: 'Download' }));
        expect(onDownload).toHaveBeenCalledWith(expect.objectContaining({ path: 'docs/notes.txt' }));

        await user.click(screen.getByRole('button', { name: 'Done' }));
        expect(onClose).toHaveBeenCalled();
    });

    it('starts over when it moves to another file', async () => {
        api.get
            .mockResolvedValueOnce({ status: 200, headers: {}, data: 'first file' })
            .mockReturnValueOnce(new Promise(() => {}));
        const { rerender, onClose, onDownload } = renderModal();
        expect(await screen.findByText('first file')).toBeInTheDocument();

        rerender(
            <PreviewModal isOpen item={item({ name: 'other.txt', path: 'other.txt' })} onClose={onClose} onDownload={onDownload} />,
        );

        expect(screen.queryByText('first file')).not.toBeInTheDocument();
        expect(screen.getByLabelText('Loading preview')).toBeInTheDocument();
    });

    it('draws a focus indicator on every control', async () => {
        api.get.mockResolvedValueOnce({ status: 200, headers: {}, data: 'hello' });
        const { baseElement } = renderModal();
        await screen.findByLabelText('Contents of notes.txt');

        expect(controlsWithoutFocusIndicator(baseElement)).toEqual([]);
    });
});
