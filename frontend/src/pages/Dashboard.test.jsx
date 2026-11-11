import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom';
import Dashboard from './Dashboard';
import { makeStore } from '../redux/store';
import { apiSlice } from '../services/apiSlice';
import { AxiosError } from 'axios';
import api from '../services/api';

vi.mock('../services/api');

const addToast = vi.fn();
vi.mock('../hooks/useToast', () => ({
    useToast: () => ({ addToast }),
}));

const TREE = [{ name: 'report.pdf', isDirectory: false, size: 10, lastModified: '2026-01-01T00:00:00Z', relativePath: 'report.pdf' }];

const PHOTOS_TREE = [
    {
        name: 'Photos', isDirectory: true, size: 0, lastModified: '2026-01-01T00:00:00Z', relativePath: 'Photos',
        children: [{ name: 'beach.jpg', isDirectory: false, size: 5, lastModified: '2026-01-01T00:00:00Z', relativePath: 'Photos/beach.jpg' }],
    },
    ...TREE,
];

// Shows the router's location and offers the browser's Back button.
const LocationProbe = () => {
    const location = useLocation();
    const navigate = useNavigate();
    return (
        <>
            <output aria-label="Location">{`${location.pathname}${location.search}`}</output>
            <button onClick={() => navigate(-1)}>Browser back</button>
        </>
    );
};

const renderDashboard = ({ url = '/dashboard' } = {}) => {
    const store = makeStore();
    render(
        <Provider store={store}>
            <MemoryRouter initialEntries={[url]}>
                <Dashboard />
                <LocationProbe />
            </MemoryRouter>
        </Provider>,
    );
    return store;
};

const currentLocation = () => screen.getByLabelText('Location').textContent;

describe('Dashboard', () => {
    afterEach(() => {
        cleanup();
        // reset, not clear: a queued mockResolvedValueOnce must not leak into the next test.
        vi.resetAllMocks();
    });

    it('downloads through a plain link instead of loading the file into memory', async () => {
        api.get.mockResolvedValue({ data: TREE });
        const user = userEvent.setup();
        const clicked = [];
        const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function record() {
            clicked.push(this.getAttribute('href'));
        });
        renderDashboard();

        await user.click(await screen.findByRole('button', { name: 'Download report.pdf' }));

        expect(clicked).toEqual(['/api/files/download?path=report.pdf']);
        // Only the tree was fetched; the file itself never went through XHR.
        expect(api.get).toHaveBeenCalledTimes(1);
        click.mockRestore();
    });

    it("previews a file in the open folder by its full path, and downloads it from there", async () => {
        const tree = [
            { ...PHOTOS_TREE[0], children: [{ ...PHOTOS_TREE[0].children[0], previewType: 'image' }] },
            ...TREE,
        ];
        api.get.mockResolvedValue({ data: tree });
        const user = userEvent.setup();
        const clicked = [];
        const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function record() {
            clicked.push(this.getAttribute('href'));
        });
        renderDashboard({ url: '/dashboard?path=Photos' });

        await user.click(await screen.findByRole('button', { name: 'Preview beach.jpg' }));

        expect(screen.getByRole('dialog', { name: 'beach.jpg' })).toBeInTheDocument();
        expect(screen.getByRole('img', { name: 'beach.jpg' })).toHaveAttribute('src', '/api/files/preview?path=Photos%2Fbeach.jpg');

        await user.click(screen.getByRole('button', { name: 'Download' }));
        expect(clicked).toEqual(['/api/files/download?path=Photos%2Fbeach.jpg']);
        click.mockRestore();
    });

    it('clears the file input so the same file can be uploaded again', async () => {
        api.get.mockResolvedValue({ data: TREE });
        api.post.mockResolvedValue({ data: {} });
        const user = userEvent.setup();
        renderDashboard();
        await screen.findByRole('button', { name: 'Download report.pdf' });
        const input = document.querySelector('input[type="file"]');

        await user.upload(input, new File(['x'], 'again.txt'));

        await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
        expect(input.value).toBe('');
    });

    it('skips files whose names start with a dot and says why', async () => {
        api.get.mockResolvedValue({ data: TREE });
        api.post.mockResolvedValue({ data: {} });
        const user = userEvent.setup();
        renderDashboard();
        await screen.findByRole('button', { name: 'Download report.pdf' });

        await user.upload(document.querySelector('input[type="file"]'), [
            new File(['x'], 'notes.txt'),
            new File(['SECRET=1'], '.env'),
        ]);

        expect(addToast).toHaveBeenCalledWith('.env was not uploaded. Names cannot start with a dot.', 'error');
        await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
        expect(api.post.mock.calls[0][1].getAll('files').map((file) => file.name)).toEqual(['notes.txt']);
    });

    it('sends nothing when every file starts with a dot', async () => {
        api.get.mockResolvedValue({ data: TREE });
        const user = userEvent.setup();
        renderDashboard();
        await screen.findByRole('button', { name: 'Download report.pdf' });

        await user.upload(document.querySelector('input[type="file"]'), [new File(['a'], '.env'), new File(['b'], '.npmrc')]);

        expect(addToast).toHaveBeenCalledWith('.env, .npmrc were not uploaded. Names cannot start with a dot.', 'error');
        expect(api.post).not.toHaveBeenCalled();
    });

    describe('uploading a folder', () => {
        // A file as the folder picker hands it over: jsdom has no webkitRelativePath of its own.
        const pickedFile = (relativePath) => {
            const file = new File(['x'], relativePath.split('/').at(-1));
            Object.defineProperty(file, 'webkitRelativePath', { value: relativePath });
            return file;
        };
        const postedPaths = () => api.post.mock.calls.map(([, form]) => form.get('path'));

        it('opens a folder picker', async () => {
            api.get.mockResolvedValue({ data: TREE });
            renderDashboard();

            expect(await screen.findByLabelText('Upload folder')).toHaveAttribute('webkitdirectory');
        });

        it('keeps its subfolders, below the open folder', async () => {
            api.get.mockResolvedValue({ data: PHOTOS_TREE });
            api.post.mockResolvedValue({ data: {} });
            const user = userEvent.setup();
            renderDashboard({ url: '/dashboard?path=Photos' });
            await screen.findByText('beach.jpg');

            await user.upload(screen.getByLabelText('Upload folder'), [
                pickedFile('Trip/plan.txt'),
                pickedFile('Trip/Day 1/arrival.jpg'),
            ]);

            await waitFor(() => expect(addToast).toHaveBeenCalledWith('Uploaded folder "Trip" (2 files) successfully.', 'success'));
            expect(postedPaths()).toEqual(['Photos/Trip', 'Photos/Trip/Day 1']);
        });

        it('leaves out dot-named files and folders, naming each once', async () => {
            api.get.mockResolvedValue({ data: TREE });
            api.post.mockResolvedValue({ data: {} });
            const user = userEvent.setup();
            renderDashboard();
            await screen.findByRole('button', { name: 'Download report.pdf' });

            await user.upload(screen.getByLabelText('Upload folder'), [
                pickedFile('Trip/plan.txt'),
                pickedFile('Trip/.DS_Store'),
                pickedFile('Trip/Day 1/.DS_Store'),
                pickedFile('Trip/.git/config'),
            ]);

            expect(addToast).toHaveBeenCalledWith('.DS_Store, .git were not uploaded. Names cannot start with a dot.', 'info');
            await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
            expect(api.post.mock.calls[0][1].getAll('files').map((file) => file.name)).toEqual(['plan.txt']);
        });

        it('sends nothing when the folder itself has a dot name', async () => {
            api.get.mockResolvedValue({ data: TREE });
            const user = userEvent.setup();
            renderDashboard();
            await screen.findByRole('button', { name: 'Download report.pdf' });

            await user.upload(screen.getByLabelText('Upload folder'), [pickedFile('.config/a.txt'), pickedFile('.config/b/c.txt')]);

            expect(addToast).toHaveBeenCalledWith('.config was not uploaded. Names cannot start with a dot.', 'info');
            expect(api.post).not.toHaveBeenCalled();
        });

        it('reports a failure and keeps both upload buttons usable afterwards', async () => {
            api.get.mockResolvedValue({ data: TREE });
            api.post.mockRejectedValueOnce({ response: { status: 400, data: { message: '"Trip" is a file, not a folder' } } });
            const user = userEvent.setup();
            renderDashboard();
            await screen.findByRole('button', { name: 'Download report.pdf' });

            await user.upload(screen.getByLabelText('Upload folder'), [pickedFile('Trip/plan.txt')]);

            await waitFor(() => expect(addToast).toHaveBeenCalledWith('"Trip" is a file, not a folder', 'error'));
            expect(screen.getByLabelText('Upload folder')).toBeEnabled();
            expect(screen.getByLabelText('Upload')).toBeEnabled();
        });
    });

    describe('dropping onto the file table', () => {
        // A drop as the browser hands it over, with FileSystemEntry stand-ins for its items.
        const fileEntry = (name) => ({ name, isFile: true, isDirectory: false, file: (resolve) => resolve(new File(['x'], name)) });
        const folderEntry = (name, children) => {
            let read = false;
            return {
                name,
                isFile: false,
                isDirectory: true,
                createReader: () => ({
                    readEntries: (resolve) => {
                        resolve(read ? [] : children);
                        read = true;
                    },
                }),
            };
        };
        const filesDrag = (...entries) => ({
            types: ['Files'],
            items: entries.map((entry) => ({ kind: 'file', webkitGetAsEntry: () => entry, getAsFile: () => null })),
        });
        const postedFolders = () =>
            api.post.mock.calls.map(([, form]) => [form.get('path'), form.getAll('files').map((file) => file.name)]);
        const dropHint = () => screen.queryByText(/^Drop to upload to/);

        it('uploads dropped files and folders below the open folder, keeping their structure', async () => {
            api.get.mockResolvedValue({ data: PHOTOS_TREE });
            api.post.mockResolvedValue({ data: {} });
            renderDashboard({ url: '/dashboard?path=Photos' });
            const row = await screen.findByText('beach.jpg');

            const dataTransfer = filesDrag(
                folderEntry('Trip', [fileEntry('plan.txt'), folderEntry('Day 1', [fileEntry('arrival.jpg')])]),
                fileEntry('notes.txt'),
            );
            fireEvent.dragEnter(row, { dataTransfer });
            expect(dropHint()).toHaveTextContent('Drop to upload to "Photos"');
            fireEvent.drop(row, { dataTransfer });

            await waitFor(() => expect(addToast).toHaveBeenCalledWith('Uploaded 3 files successfully.', 'success'));
            expect(postedFolders()).toEqual([
                ['Photos', ['notes.txt']],
                ['Photos/Trip', ['plan.txt']],
                ['Photos/Trip/Day 1', ['arrival.jpg']],
            ]);
            expect(dropHint()).not.toBeInTheDocument();
        });

        it('names the folder when a single folder is dropped, and reports what it left out', async () => {
            api.get.mockResolvedValue({ data: TREE });
            api.post.mockResolvedValue({ data: {} });
            renderDashboard();
            const row = await screen.findByRole('button', { name: 'Download report.pdf' });

            fireEvent.drop(row, { dataTransfer: filesDrag(folderEntry('Trip', [fileEntry('plan.txt'), fileEntry('.DS_Store')])) });


            await waitFor(() => expect(addToast).toHaveBeenCalledWith('Uploaded folder "Trip" (1 file) successfully.', 'success'));
            expect(addToast).toHaveBeenCalledWith('.DS_Store was not uploaded. Names cannot start with a dot.', 'info');
        });

        it('shows the drop state only while files are over the table', async () => {
            api.get.mockResolvedValue({ data: TREE });
            renderDashboard();
            const row = await screen.findByRole('button', { name: 'Download report.pdf' });
            const header = screen.getByRole('columnheader', { name: 'Name' });

            fireEvent.dragEnter(row, { dataTransfer: filesDrag() });
            expect(dropHint()).toHaveTextContent('Drop to upload to My Files');

            // Moving to another part of the table enters it before leaving the one before.
            fireEvent.dragEnter(header, { dataTransfer: filesDrag() });
            fireEvent.dragLeave(row, { dataTransfer: filesDrag() });
            expect(dropHint()).toBeInTheDocument();

            fireEvent.dragLeave(header, { dataTransfer: filesDrag() });
            expect(dropHint()).not.toBeInTheDocument();
        });

        // The browser ends a drag cancelled with Esc with a dragleave on what is under the pointer.
        it('drops the drop state when the drag is cancelled over the table', async () => {
            api.get.mockResolvedValue({ data: TREE });
            renderDashboard();
            const row = await screen.findByRole('button', { name: 'Download report.pdf' });

            fireEvent.dragEnter(row, { dataTransfer: filesDrag() });
            fireEvent.dragLeave(row, { dataTransfer: filesDrag() });

            expect(dropHint()).not.toBeInTheDocument();
            fireEvent.dragEnter(row, { dataTransfer: filesDrag() });
            expect(dropHint()).toBeInTheDocument();
        });

        it('takes nothing dragged from the page itself, such as the logo', async () => {
            api.get.mockResolvedValue({ data: TREE });
            renderDashboard();
            const row = await screen.findByRole('button', { name: 'Download report.pdf' });

            fireEvent.dragStart(row);
            expect(fireEvent.dragEnter(row, { dataTransfer: filesDrag() })).toBe(true);
            expect(dropHint()).not.toBeInTheDocument();
            fireEvent.drop(row, { dataTransfer: filesDrag(fileEntry('logo.png')) });
            fireEvent.dragEnd(row);

            fireEvent.dragEnter(row, { dataTransfer: filesDrag() });
            expect(dropHint()).toBeInTheDocument();
            expect(api.post).not.toHaveBeenCalled();
        });

        it('says so when a drop holds nothing but empty folders', async () => {
            api.get.mockResolvedValue({ data: TREE });
            renderDashboard();
            const row = await screen.findByRole('button', { name: 'Download report.pdf' });

            fireEvent.drop(row, { dataTransfer: filesDrag(folderEntry('Empty', [])) });

            await waitFor(() => expect(addToast).toHaveBeenCalledWith('Nothing was uploaded: empty folders are left out.', 'info'));
            expect(api.post).not.toHaveBeenCalled();
            expect(screen.getByLabelText('Upload')).toBeEnabled();
        });

        it('stops the browser opening files dropped beside the table, but leaves other drags alone', async () => {
            api.get.mockResolvedValue({ data: TREE });
            renderDashboard();
            const heading = await screen.findByRole('heading', { name: 'My Files' });

            expect(fireEvent.dragOver(heading, { dataTransfer: filesDrag() })).toBe(false);
            expect(fireEvent.drop(heading, { dataTransfer: filesDrag(fileEntry('a.txt')) })).toBe(false);
            expect(fireEvent.drop(heading, { dataTransfer: { types: ['text/plain'], items: [] } })).toBe(true);
            expect(api.post).not.toHaveBeenCalled();
        });

        it('is busy while a dropped folder is still being read', async () => {
            api.get.mockResolvedValue({ data: TREE });
            renderDashboard();
            const row = await screen.findByRole('button', { name: 'Download report.pdf' });
            // A file the browser never finishes handing over.
            const stuck = { name: 'big.iso', isFile: true, isDirectory: false, file: () => {} };

            fireEvent.drop(row, { dataTransfer: filesDrag(stuck) });
            await waitFor(() => expect(screen.getByLabelText('Uploading…')).toBeDisabled());
            expect(screen.getByLabelText('Upload folder')).toBeDisabled();

            fireEvent.dragEnter(row, { dataTransfer: filesDrag() });
            expect(dropHint()).not.toBeInTheDocument();
            expect(screen.getByText('Wait for the current upload to finish')).toBeInTheDocument();
            fireEvent.drop(row, { dataTransfer: filesDrag(fileEntry('b.txt')) });
            expect(addToast).toHaveBeenCalledWith('Wait for the current upload to finish, then drop again.', 'info');
            expect(api.post).not.toHaveBeenCalled();
        });

        it('says so when the dropped files cannot be read, and is ready again afterwards', async () => {
            api.get.mockResolvedValue({ data: TREE });
            renderDashboard();
            const row = await screen.findByRole('button', { name: 'Download report.pdf' });
            const unreadable = { name: 'locked.txt', isFile: true, isDirectory: false, file: (_, reject) => reject(new Error('denied')) };

            fireEvent.drop(row, { dataTransfer: filesDrag(unreadable) });

            await waitFor(() => expect(addToast).toHaveBeenCalledWith('Could not read the dropped files.', 'error'));
            expect(screen.getByLabelText('Upload')).toBeEnabled();
            expect(api.post).not.toHaveBeenCalled();
        });

        it('leaves drags without files to the browser', async () => {
            api.get.mockResolvedValue({ data: TREE });
            renderDashboard();
            const row = await screen.findByRole('button', { name: 'Download report.pdf' });
            const dataTransfer = { types: ['text/plain'], items: [] };

            expect(fireEvent.dragOver(row, { dataTransfer })).toBe(true);
            expect(dropHint()).not.toBeInTheDocument();
            expect(fireEvent.drop(row, { dataTransfer })).toBe(true);
            expect(api.post).not.toHaveBeenCalled();
        });

        it('turns a drop away while another upload is running', async () => {
            api.get.mockResolvedValue({ data: TREE });
            api.post.mockReturnValueOnce(new Promise(() => {}));
            renderDashboard();
            const row = await screen.findByRole('button', { name: 'Download report.pdf' });

            fireEvent.drop(row, { dataTransfer: filesDrag(fileEntry('a.txt')) });
            await waitFor(() => expect(screen.getByLabelText('Uploading…')).toBeDisabled());
            fireEvent.drop(row, { dataTransfer: filesDrag(fileEntry('b.txt')) });

            expect(addToast).toHaveBeenCalledWith('Wait for the current upload to finish, then drop again.', 'info');
            expect(api.post).toHaveBeenCalledTimes(1);
        });
    });

    // The version dialog restores through api itself until #396; the dashboard then lists again.
    it('lists the files again after a version is restored', async () => {
        const tracked = [{ ...TREE[0], id: 7 }];
        api.get.mockImplementation((url) =>
            Promise.resolve({
                data: url === '/api/files' ? tracked : [{ id: 1, version: 1, size: 5, createdAt: '2026-01-01T00:00:00Z', createdBy: 'ada' }],
            }),
        );
        api.post.mockResolvedValueOnce({ data: { message: 'Version 1 restored' } });
        const user = userEvent.setup();
        renderDashboard();

        await user.click(await screen.findByRole('button', { name: 'Versions of report.pdf' }));
        await screen.findByText('Version 1');
        await user.click(screen.getAllByRole('button', { name: 'Restore' }).at(-1));

        await waitFor(() => expect(api.get.mock.calls.filter(([url]) => url === '/api/files')).toHaveLength(2));
    });

    it('says what failed when an upload gets no answer at all', async () => {
        api.get.mockResolvedValue({ data: TREE });
        api.post.mockRejectedValueOnce(new AxiosError('Network Error', AxiosError.ERR_NETWORK));
        const user = userEvent.setup();
        renderDashboard();
        await screen.findByRole('button', { name: 'Download report.pdf' });

        await user.upload(screen.getByLabelText('Upload'), new File(['a'], 'a.txt'));

        await waitFor(() => expect(addToast).toHaveBeenCalledWith('Failed to upload files.', 'error'));
    });

    // A failed refresh is reported, but not while the next listing, which may well work, is on its way.
    it('shows a failed refresh only until the next listing starts', async () => {
        api.get.mockResolvedValueOnce({ data: TREE });
        const store = renderDashboard();
        await screen.findByRole('button', { name: 'Download report.pdf' });

        api.get.mockRejectedValueOnce({ response: { status: 500, data: '' } });
        store.dispatch(apiSlice.util.invalidateTags(['Files']));
        expect(await screen.findByText(/Could not refresh the file list/)).toBeInTheDocument();

        api.get.mockReturnValueOnce(new Promise(() => {}));
        store.dispatch(apiSlice.util.invalidateTags(['Files']));
        await waitFor(() => expect(screen.queryByText(/Could not refresh the file list/)).not.toBeInTheDocument());
        expect(screen.getByRole('button', { name: 'Download report.pdf' })).toBeInTheDocument();
    });

    it('keeps the table on screen while the list refreshes', async () => {
        api.get.mockResolvedValueOnce({ data: TREE });
        const store = renderDashboard();
        await screen.findByRole('button', { name: 'Download report.pdf' });

        // A refresh that hasn't answered yet, as after every upload, delete or restore.
        api.get.mockReturnValueOnce(new Promise(() => {}));
        store.dispatch(apiSlice.util.invalidateTags(['Files']));

        expect(screen.getByRole('button', { name: 'Download report.pdf' })).toBeInTheDocument();
        expect(screen.queryByLabelText('Loading files')).not.toBeInTheDocument();
    });

    it('keeps the new-folder dialog open with the reason when the folder already exists', async () => {
        api.get.mockResolvedValue({ data: TREE });
        api.post.mockRejectedValueOnce({ response: { status: 409, data: { message: 'Photos already exists' } } });
        const user = userEvent.setup();
        renderDashboard();

        await user.click(await screen.findByRole('button', { name: /New Folder/ }));
        await user.type(screen.getByPlaceholderText('Folder Name'), 'Photos');
        await user.click(screen.getByRole('button', { name: 'Create' }));

        expect(await screen.findByRole('alert')).toHaveTextContent('Photos already exists');
        expect(screen.getByRole('dialog', { name: /Create New Folder/ })).toBeInTheDocument();
        expect(screen.getByPlaceholderText('Folder Name')).toHaveValue('Photos');
    });

    describe('the current folder', () => {
        it('is kept in the URL when a folder is opened', async () => {
            api.get.mockResolvedValue({ data: PHOTOS_TREE });
            const user = userEvent.setup();
            renderDashboard();

            await user.click(await screen.findByRole('button', { name: 'Photos' }));

            expect(currentLocation()).toBe('/dashboard?path=Photos');
            expect(screen.getByText('beach.jpg')).toBeInTheDocument();
        });

        it('is read back from the URL, so a reload stays in the folder', async () => {
            api.get.mockResolvedValue({ data: PHOTOS_TREE });
            renderDashboard({ url: '/dashboard?path=Photos' });

            expect(await screen.findByText('beach.jpg')).toBeInTheDocument();
            expect(screen.queryByRole('button', { name: 'Photos' })).not.toBeInTheDocument();
        });

        it('follows Back to the previous folder', async () => {
            api.get.mockResolvedValue({ data: PHOTOS_TREE });
            const user = userEvent.setup();
            renderDashboard();

            await user.click(await screen.findByRole('button', { name: 'Photos' }));
            await user.click(screen.getByRole('button', { name: 'Browser back' }));

            expect(currentLocation()).toBe('/dashboard');
            expect(screen.getByRole('button', { name: 'Photos' })).toBeInTheDocument();
        });

        it('is where uploads go', async () => {
            api.get.mockResolvedValue({ data: PHOTOS_TREE });
            api.post.mockResolvedValue({ data: {} });
            const user = userEvent.setup();
            renderDashboard({ url: '/dashboard?path=Photos' });
            await screen.findByText('beach.jpg');

            await user.upload(document.querySelector('input[type="file"]'), new File(['x'], 'sunset.jpg'));

            await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
            expect(api.post.mock.calls[0][1].get('path')).toBe('Photos');
        });
    });

    it('does not offer upload sources or installers that do not exist', async () => {
        api.get.mockResolvedValue({ data: TREE });
        renderDashboard();
        await screen.findByRole('button', { name: 'Download report.pdf' });

        expect(screen.queryByText(/Google Drive|OneDrive|Install App/)).not.toBeInTheDocument();
    });
});
