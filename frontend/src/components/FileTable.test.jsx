import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import FileTable from './FileTable';

afterEach(cleanup);

// lastModified arrives from the backend already formatted as "MM/dd/yyyy hh:mm a"
// (FileServingService.DATE_FORMATTER), so the fixtures use that exact shape.
const APPLES_FILES = [
    { name: 'budget.xlsx', isDirectory: false, size: 512, lastModified: '02/02/2026 10:00 AM', relativePath: 'apples/budget.xlsx' },
    { name: 'photo.png', isDirectory: false, size: 900, lastModified: '02/03/2026 10:00 AM', relativePath: 'apples/photo.png' },
];

const ROOT_FILES = [
    {
        name: 'Zebra Folder', isDirectory: true, size: 96, lastModified: '01/05/2026 09:00 AM', relativePath: 'Zebra Folder',
        children: [
            { name: 'deep.txt', isDirectory: false, size: 10, lastModified: '04/01/2026 08:00 AM', relativePath: 'Zebra Folder/deep.txt' },
            {
                name: 'nested', isDirectory: true, size: 20, lastModified: '04/02/2026 08:00 AM', relativePath: 'Zebra Folder/nested',
                children: [
                    { name: 'buried.log', isDirectory: false, size: 20, lastModified: '04/03/2026 08:00 AM', relativePath: 'Zebra Folder/nested/buried.log' },
                ],
            },
        ],
    },
    { name: 'apples', isDirectory: true, size: 128, lastModified: '12/31/2025 11:59 PM', relativePath: 'apples', children: [...APPLES_FILES] },
    { name: 'notes.txt', isDirectory: false, size: 2048, lastModified: '09/09/2026 02:38 PM', relativePath: 'notes.txt' },
    { name: 'Big.zip', isDirectory: false, size: 1048576, lastModified: '03/04/2026 02:38 AM', relativePath: 'Big.zip' },
    { name: 'tiny.md', isDirectory: false, size: 12, lastModified: '01/02/2026 12:05 AM', relativePath: 'tiny.md' },
];

const noopHandlers = {
    onDelete: vi.fn(),
    onDownload: vi.fn(),
    onShare: vi.fn(),
    onFolderClick: vi.fn(),
};

const renderTable = (props = {}) =>
    render(<FileTable files={ROOT_FILES} currentPath="" {...noopHandlers} {...props} />);

// The name cell holds the filename followed by its relative path; the filename is the
// first child of the label wrapper, so read that rather than the whole cell's text.
const visibleOrder = () =>
    screen
        .getAllByRole('row')
        .slice(1)
        .map((row) => within(row).getAllByRole('cell')[0].querySelector('.ml-4').firstElementChild.textContent);

const visiblePaths = () =>
    screen
        .getAllByRole('row')
        .slice(1)
        .map((row) => within(row).getAllByRole('cell')[0].querySelector('.ml-4').lastElementChild.textContent);

const header = (name) => screen.getByRole('button', { name });
const columnHeader = (name) => screen.getByRole('columnheader', { name });

describe('FileTable default ordering', () => {
    it('groups folders ahead of files, each sorted by name ascending', () => {
        renderTable();
        expect(visibleOrder()).toEqual(['apples', 'Zebra Folder', 'Big.zip', 'notes.txt', 'tiny.md']);
    });

    it('marks the name column as the active ascending sort', () => {
        renderTable();
        expect(columnHeader('Name')).toHaveAttribute('aria-sort', 'ascending');
        expect(columnHeader('Size')).toHaveAttribute('aria-sort', 'none');
    });
});

describe('FileTable search', () => {
    it('filters by filename with a case-insensitive substring match', async () => {
        const user = userEvent.setup();
        renderTable();

        await user.type(screen.getByRole('textbox', { name: 'Search files' }), 'P');

        // Matches come from every level, folders first, then files by name
        expect(visibleOrder()).toEqual(['apples', 'Big.zip', 'deep.txt', 'photo.png']);
    });

    it('reports when nothing matches instead of rendering an empty table', async () => {
        const user = userEvent.setup();
        renderTable();

        await user.type(screen.getByRole('textbox', { name: 'Search files' }), 'zzz');

        expect(screen.getByText('No files match your search.')).toBeInTheDocument();
    });

    it('keeps folders grouped ahead of files within the filtered results', async () => {
        const user = userEvent.setup();
        renderTable();

        await user.type(screen.getByRole('textbox', { name: 'Search files' }), 'nested');

        expect(visibleOrder()).toEqual(['nested']);
    });
});

describe('FileTable sorting', () => {
    it('sorts by size within each group and toggles direction on repeat click', async () => {
        const user = userEvent.setup();
        renderTable();

        await user.click(header('Size'));
        expect(visibleOrder()).toEqual(['Zebra Folder', 'apples', 'tiny.md', 'notes.txt', 'Big.zip']);
        expect(columnHeader('Size')).toHaveAttribute('aria-sort', 'ascending');

        await user.click(header('Size'));
        expect(visibleOrder()).toEqual(['apples', 'Zebra Folder', 'Big.zip', 'notes.txt', 'tiny.md']);
        expect(columnHeader('Size')).toHaveAttribute('aria-sort', 'descending');
    });

    it('sorts by last modified date, not by the formatted string', async () => {
        const user = userEvent.setup();
        renderTable();

        await user.click(header('Last Modified'));

        // 12/31/2025 sorts before 01/05/2026, which a lexical string compare would get wrong
        expect(visibleOrder()).toEqual(['apples', 'Zebra Folder', 'tiny.md', 'Big.zip', 'notes.txt']);
    });

    it('starts a newly selected column ascending rather than inheriting the previous direction', async () => {
        const user = userEvent.setup();
        renderTable();

        await user.click(header('Name'));
        expect(columnHeader('Name')).toHaveAttribute('aria-sort', 'descending');

        await user.click(header('Size'));
        expect(columnHeader('Size')).toHaveAttribute('aria-sort', 'ascending');
        expect(columnHeader('Name')).toHaveAttribute('aria-sort', 'none');
    });

    it('can be driven from the keyboard', async () => {
        const user = userEvent.setup();
        renderTable();

        header('Size').focus();
        await user.keyboard('{Enter}');

        expect(columnHeader('Size')).toHaveAttribute('aria-sort', 'ascending');
    });
});

describe('FileTable folder navigation', () => {
    it('clears the search when the user navigates to another folder', async () => {
        const user = userEvent.setup();
        const { rerender } = renderTable();

        await user.type(screen.getByRole('textbox', { name: 'Search files' }), 'apple');
        expect(visibleOrder()).toEqual(['apples']);

        // Dashboard swaps the file list and path when a folder is opened
        rerender(<FileTable files={APPLES_FILES} currentPath="apples" {...noopHandlers} />);

        expect(screen.getByRole('textbox', { name: 'Search files' })).toHaveValue('');
        expect(visibleOrder()).toEqual(['budget.xlsx', 'photo.png']);
    });

    it('keeps the chosen sort order across navigation', async () => {
        const user = userEvent.setup();
        const { rerender } = renderTable();

        await user.click(header('Size'));
        await user.click(header('Size'));
        expect(columnHeader('Size')).toHaveAttribute('aria-sort', 'descending');

        rerender(<FileTable files={APPLES_FILES} currentPath="apples" {...noopHandlers} />);

        expect(columnHeader('Size')).toHaveAttribute('aria-sort', 'descending');
        expect(visibleOrder()).toEqual(['photo.png', 'budget.xlsx']);
    });
});

describe('FileTable recursive search', () => {
    it('finds files inside subfolders and labels each with its path', async () => {
        const user = userEvent.setup();
        renderTable();

        await user.type(screen.getByRole('textbox', { name: 'Search files' }), 'buried');

        expect(visibleOrder()).toEqual(['buried.log']);
        expect(visiblePaths()).toEqual(['Zebra Folder/nested/buried.log']);
    });

    it('still returns folders whose own name matches, grouped ahead of files', async () => {
        const user = userEvent.setup();
        renderTable();

        await user.type(screen.getByRole('textbox', { name: 'Search files' }), 'e');

        const order = visibleOrder();
        expect(order.slice(0, 3)).toEqual(['apples', 'nested', 'Zebra Folder']);
        expect(order).toContain('buried.log');
        expect(order).toContain('notes.txt');
    });

    it('reports how many matches were found below the current folder', async () => {
        const user = userEvent.setup();
        renderTable();

        await user.type(screen.getByRole('textbox', { name: 'Search files' }), 'buried');

        expect(screen.getByText('1 match in this folder and below')).toBeInTheDocument();
    });

    it('limits the search to the folder being viewed, not the whole tree', async () => {
        const user = userEvent.setup();
        render(<FileTable files={APPLES_FILES} currentPath="apples" {...noopHandlers} />);

        await user.type(screen.getByRole('textbox', { name: 'Search files' }), 'buried');

        expect(screen.getByText('No files match your search.')).toBeInTheDocument();
    });

    it('navigates by full path so a nested folder result opens the right folder', async () => {
        const user = userEvent.setup();
        const onFolderClick = vi.fn();
        render(<FileTable files={ROOT_FILES} currentPath="" {...noopHandlers} onFolderClick={onFolderClick} />);

        await user.type(screen.getByRole('textbox', { name: 'Search files' }), 'nested');
        await user.click(screen.getByRole('button', { name: 'nested' }));

        expect(onFolderClick).toHaveBeenCalledWith('Zebra Folder/nested');
    });
});
