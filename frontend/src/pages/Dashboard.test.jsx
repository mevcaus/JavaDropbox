import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { configureStore } from '@reduxjs/toolkit';
import { Provider } from 'react-redux';
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom';
import Dashboard from './Dashboard';
import filesReducer, { fetchFiles } from '../features/filesSlice';
import authReducer from '../features/authSlice';
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
    const store = configureStore({ reducer: { auth: authReducer, files: filesReducer } });
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

    it('keeps the table on screen while the list refreshes', async () => {
        api.get.mockResolvedValueOnce({ data: TREE });
        const store = renderDashboard();
        await screen.findByRole('button', { name: 'Download report.pdf' });

        store.dispatch(fetchFiles.pending('refresh'));

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
