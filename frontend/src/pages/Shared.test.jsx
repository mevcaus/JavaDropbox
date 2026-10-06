import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import Shared from './Shared';
import api from '../services/api';
import { controlsWithoutFocusIndicator } from '../test/focusIndicator';

vi.mock('../services/api');

const EXPIRES = '2026-11-01T12:00:00Z';

const file = (overrides) => ({
    name: 'notes.txt',
    isDirectory: false,
    size: 2048,
    lastModified: '2026-10-01T12:00:00Z',
    previewType: 'text',
    expiresAt: EXPIRES,
    contents: null,
    ...overrides,
});

const folder = {
    name: 'Trip',
    isDirectory: true,
    size: 3072,
    lastModified: '2026-10-01T12:00:00Z',
    previewType: null,
    expiresAt: EXPIRES,
    contents: [
        {
            name: 'Day 1',
            isDirectory: true,
            size: 1024,
            children: [{ name: 'beach.jpg', isDirectory: false, size: 1024, children: null }],
        },
        { name: 'Empty', isDirectory: true, size: 0, children: [] },
        { name: 'plan.md', isDirectory: false, size: 2048, children: null },
    ],
};

// Answers the link's description with info, and a text preview with its content.
const serve = (info, text = 'hello\n') =>
    api.get.mockImplementation(async (url) => {
        if (url.endsWith('/info')) return { data: info };
        return { status: 200, headers: {}, data: text };
    });

const renderAt = (token = 'tok_EN-1') =>
    render(
        <MemoryRouter initialEntries={[`/share/${token}`]}>
            <Routes>
                <Route path="/share/:token" element={<Shared />} />
            </Routes>
        </MemoryRouter>,
    );

describe('Shared', () => {
    afterEach(() => {
        cleanup();
        vi.resetAllMocks();
        document.title = 'JavaDropbox';
    });

    it('describes a shared file and previews it, downloading nothing until asked', async () => {
        serve(file());
        renderAt();

        expect(await screen.findByRole('heading', { name: 'notes.txt' })).toBeInTheDocument();
        expect(screen.getByText(/^2 KB · Link expires /)).toBeInTheDocument();
        expect((await screen.findByLabelText('Contents of notes.txt')).textContent).toBe('hello\n');
        expect(api.get).toHaveBeenCalledWith('/share/tok_EN-1/info');
        expect(api.get).toHaveBeenCalledWith('/share/tok_EN-1/preview', expect.objectContaining({ responseType: 'text' }));

        const download = screen.getByRole('link', { name: 'Download' });
        expect(download).toHaveAttribute('href', '/share/tok_EN-1/download');
        expect(download).toHaveAttribute('download');
        expect(document.title).toBe('notes.txt · JavaDropbox');
    });

    it('previews images and PDFs from the link', async () => {
        serve(file({ name: 'beach.jpg', previewType: 'image' }));
        renderAt();
        expect(await screen.findByRole('img', { name: 'beach.jpg' })).toHaveAttribute('src', '/share/tok_EN-1/preview');
        cleanup();

        serve(file({ name: 'report.pdf', previewType: 'pdf' }));
        renderAt();
        expect(await screen.findByTitle('Preview of report.pdf')).toHaveAttribute('src', '/share/tok_EN-1/preview');
    });

    it('says when a file has no preview, and still offers the download', async () => {
        serve(file({ name: 'archive.7z', previewType: null }));
        renderAt();

        expect(await screen.findByText(/no preview for this kind of file/)).toBeInTheDocument();
        expect(screen.getByRole('link', { name: 'Download' })).toHaveAttribute('href', '/share/tok_EN-1/download');
        expect(api.get).toHaveBeenCalledTimes(1);
    });

    it('lists a shared folder and opens the folders in it', async () => {
        const user = userEvent.setup();
        serve(folder);
        renderAt();

        expect(await screen.findByRole('heading', { name: 'Trip' })).toBeInTheDocument();
        expect(screen.getByText(/^Folder · 3 items · 3 KB · Link expires /)).toBeInTheDocument();
        expect(screen.getByRole('link', { name: 'Download as .zip' })).toHaveAttribute('href', '/share/tok_EN-1/download');

        const list = screen.getByRole('list');
        expect(within(list).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
            'Day 11 item',
            'Empty0 items',
            'plan.md2 KB',
        ]);
        // Files are listed, not opened: the folder is downloaded whole.
        expect(within(list).queryByRole('button', { name: 'plan.md' })).not.toBeInTheDocument();

        await user.click(screen.getByRole('button', { name: 'Day 1' }));
        const trail = screen.getByRole('navigation', { name: 'Folder' });
        expect(within(trail).getByText('Day 1')).toHaveAttribute('aria-current', 'page');
        expect(screen.getByText('beach.jpg')).toBeInTheDocument();

        await user.click(within(trail).getByRole('button', { name: 'Trip' }));
        expect(screen.getByRole('button', { name: 'Empty' })).toBeInTheDocument();
        await user.click(screen.getByRole('button', { name: 'Empty' }));
        expect(screen.getByText('This folder is empty.')).toBeInTheDocument();
    });

    it('explains a link that no longer works', async () => {
        api.get.mockRejectedValue({ response: { status: 404, data: '' } });
        renderAt();

        expect(await screen.findByRole('heading', { name: "This link doesn't work" })).toBeInTheDocument();
        expect(screen.getByText(/expired or been revoked/)).toBeInTheDocument();
        expect(screen.queryByRole('link', { name: /Download/ })).not.toBeInTheDocument();
    });

    it('reports any other failure as an error', async () => {
        api.get.mockRejectedValue({ message: 'Network Error' });
        renderAt();

        expect(await screen.findByRole('alert')).toBeInTheDocument();
        expect(screen.queryByRole('heading', { name: "This link doesn't work" })).not.toBeInTheDocument();
    });

    it('draws a focus indicator on every control', async () => {
        serve(folder);
        renderAt();
        await screen.findByRole('heading', { name: 'Trip' });
        await userEvent.setup().click(screen.getByRole('button', { name: 'Day 1' }));

        expect(controlsWithoutFocusIndicator(document.body)).toEqual([]);
    });
});
