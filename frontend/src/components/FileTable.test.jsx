import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import FileTable from './FileTable';
import api from '../services/api';
import { controlsWithoutFocusIndicator } from '../test/focusIndicator';

// Searches go to the server; each test that searches says what it answers.
vi.mock('../services/api');

afterEach(() => {
    cleanup();
    // reset, not clear: a queued mockResolvedValueOnce must not leak into the next test.
    vi.resetAllMocks();
});

// lastModified arrives from the backend as absolute ISO-8601 instants, so the fixtures use
// that exact shape, including the trailing Z.
const APPLES_FILES = [
    { name: 'budget.xlsx', isDirectory: false, size: 512, lastModified: '2026-02-02T10:00:00Z', relativePath: 'apples/budget.xlsx' },
    { name: 'photo.png', isDirectory: false, size: 900, lastModified: '2026-02-03T10:00:00Z', relativePath: 'apples/photo.png' },
];

const ROOT_FILES = [
    {
        name: 'Zebra Folder', isDirectory: true, size: 96, lastModified: '2026-01-05T09:00:00Z', relativePath: 'Zebra Folder',
        children: [
            { name: 'deep.txt', isDirectory: false, size: 10, lastModified: '2026-04-01T08:00:00Z', relativePath: 'Zebra Folder/deep.txt' },
            {
                name: 'nested', isDirectory: true, size: 20, lastModified: '2026-04-02T08:00:00Z', relativePath: 'Zebra Folder/nested',
                children: [
                    { name: 'buried.log', isDirectory: false, size: 20, lastModified: '2026-04-03T08:00:00Z', relativePath: 'Zebra Folder/nested/buried.log' },
                ],
            },
        ],
    },
    { name: 'apples', isDirectory: true, size: 128, lastModified: '2025-12-31T23:59:00Z', relativePath: 'apples', children: [...APPLES_FILES] },
    { name: 'notes.txt', isDirectory: false, size: 2048, lastModified: '2026-09-09T14:38:00Z', relativePath: 'notes.txt' },
    { name: 'Big.zip', isDirectory: false, size: 1048576, lastModified: '2026-03-04T02:38:00Z', relativePath: 'Big.zip' },
    { name: 'tiny.md', isDirectory: false, size: 12, lastModified: '2026-01-02T00:05:00Z', relativePath: 'tiny.md' },
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
        .map((row) => within(row).getAllByRole('cell')[0].querySelector('.ml-4 > div.text-xs').textContent);

// The tree's node at a path in ROOT_FILES.
const nodeAt = (path, nodes = ROOT_FILES) => {
    for (const node of nodes) {
        if (node.relativePath === path) return node;
        const found = node.children && nodeAt(path, node.children);
        if (found) return found;
    }
    return undefined;
};

// A search result for a node, as GET /api/search describes it.
const hit = (node, snippet = null) => ({
    name: node.name,
    relativePath: node.relativePath,
    isDirectory: node.isDirectory,
    size: node.isDirectory ? null : node.size,
    lastModified: node.lastModified,
    previewType: node.previewType ?? null,
    snippet,
});

// The server's answer to a search, best match first.
const answer = (results, { total = results.length, indexing = false } = {}) => ({
    data: { results, total, indexing },
});

const searchBox = () => screen.getByRole('textbox', { name: 'Search files' });

// Types a search and waits for the server's answer to be shown.
const searchFor = async (user, text) => {
    await user.type(searchBox(), text);
    await waitFor(() => expect(screen.queryByText(/Searching…/)).not.toBeInTheDocument());
};

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
    it('asks the server about the open folder and everything below it, and lists the answer best first', async () => {
        api.get.mockResolvedValue(answer([hit(nodeAt('notes.txt')), hit(nodeAt('Zebra Folder/nested/buried.log')), hit(nodeAt('apples'))]));
        const user = userEvent.setup();
        renderTable();

        await searchFor(user, 'report');

        expect(api.get).toHaveBeenCalledWith('/api/search', {
            params: { q: 'report', path: '' },
            signal: expect.any(AbortSignal),
        });
        // The server's order, not folders first.
        expect(visibleOrder()).toEqual(['notes.txt', 'buried.log', 'apples']);
        expect(visiblePaths()).toEqual(['notes.txt', 'Zebra Folder/nested/buried.log', 'apples']);
        expect(screen.getByText('3 matches in this folder and below, best first.')).toBeInTheDocument();
    });

    it('waits for typing to pause rather than asking for every letter', async () => {
        api.get.mockResolvedValue(answer([hit(nodeAt('notes.txt'))]));
        const user = userEvent.setup();
        renderTable();

        await searchFor(user, 'notes');

        expect(api.get).toHaveBeenCalledTimes(1);
        expect(api.get.mock.calls[0][1].params.q).toBe('notes');
    });

    it('searches the folder being viewed, not the whole tree', async () => {
        api.get.mockResolvedValue(answer([hit(APPLES_FILES[0])]));
        const user = userEvent.setup();
        render(<FileTable files={APPLES_FILES} currentPath="apples" {...noopHandlers} />);

        await searchFor(user, 'budget');

        expect(api.get.mock.calls[0][1].params).toEqual({ q: 'budget', path: 'apples' });
    });

    it('shows the passage of text that matched, with the matching words marked', async () => {
        const snippet = { text: '…the quarterly budget is due on Friday…', highlights: [{ start: 15, end: 21 }] };
        api.get.mockResolvedValue(answer([hit(nodeAt('notes.txt'), snippet)]));
        const user = userEvent.setup();
        renderTable();

        await searchFor(user, 'budget');

        const mark = screen.getByText('budget', { selector: 'mark' });
        expect(mark.parentElement).toHaveTextContent('…the quarterly budget is due on Friday…');
    });

    it("shows markup in a file's text as text", async () => {
        const snippet = { text: '<img src=x onerror=alert(1)> budget', highlights: [{ start: 29, end: 35 }] };
        api.get.mockResolvedValue(answer([hit(nodeAt('notes.txt'), snippet)]));
        const user = userEvent.setup();
        const { container } = renderTable();

        await searchFor(user, 'budget');

        expect(container.querySelector('img')).toBeNull();
        expect(screen.getByText('budget', { selector: 'mark' }).parentElement).toHaveTextContent(snippet.text);
    });

    it('shows a result as the tree has it, so a file the app tracks offers its versions', async () => {
        const tracked = { name: 'plan.txt', isDirectory: false, size: 5, lastModified: '2026-01-01T00:00:00Z', relativePath: 'docs/plan.txt', id: 7 };
        const tree = [{ name: 'docs', isDirectory: true, size: 5, lastModified: '2026-01-01T00:00:00Z', relativePath: 'docs', children: [tracked] }];
        api.get.mockResolvedValue(answer([hit(tracked)]));
        const onVersions = vi.fn();
        const user = userEvent.setup();
        render(<FileTable files={tree} currentPath="" {...noopHandlers} onVersions={onVersions} />);

        await searchFor(user, 'plan');
        await user.click(screen.getByRole('button', { name: 'Versions of plan.txt' }));

        expect(onVersions).toHaveBeenCalledWith(expect.objectContaining({ id: 7, relativePath: 'docs/plan.txt' }));
    });

    it('still lists a result the tree does not have yet, such as a file uploaded elsewhere', async () => {
        const fresh = { name: 'fresh.txt', isDirectory: false, size: 3, lastModified: '2026-05-01T00:00:00Z', relativePath: 'new/fresh.txt' };
        api.get.mockResolvedValue(answer([hit(fresh)]));
        const onDownload = vi.fn();
        const user = userEvent.setup();
        renderTable({ onDownload });

        await searchFor(user, 'fresh');
        await user.click(screen.getByRole('button', { name: 'Download fresh.txt' }));

        expect(onDownload).toHaveBeenCalledWith(expect.objectContaining({ relativePath: 'new/fresh.txt' }));
    });

    it('reports when nothing matches instead of rendering an empty table', async () => {
        api.get.mockResolvedValue(answer([]));
        const user = userEvent.setup();
        renderTable();

        await searchFor(user, 'zzz');

        expect(screen.getByText('No files match your search.')).toBeInTheDocument();
        expect(screen.getByText('No matches in this folder or the folders below it.')).toBeInTheDocument();
    });

    it('says when only the best of the matches are shown', async () => {
        api.get.mockResolvedValue(answer([hit(nodeAt('notes.txt')), hit(nodeAt('tiny.md'))], { total: 120 }));
        const user = userEvent.setup();
        renderTable();

        await searchFor(user, 'note');

        expect(screen.getByText('120 matches in this folder and below, best first — showing the best 2.')).toBeInTheDocument();
    });

    it('says when the index is still catching up and some files may be missing', async () => {
        api.get.mockResolvedValue(answer([hit(nodeAt('notes.txt'))], { indexing: true }));
        const user = userEvent.setup();
        renderTable();

        await searchFor(user, 'note');

        expect(screen.getByText(/Still indexing files, so some may be missing\./)).toBeInTheDocument();
    });

    it('reports a search that failed', async () => {
        api.get.mockRejectedValue({ response: { status: 500 } });
        const user = userEvent.setup();
        renderTable();

        await searchFor(user, 'note');

        expect(screen.getByText('The server is unavailable right now. Please try again.')).toBeInTheDocument();
    });

    it('drops an answer that arrives after a newer search has started', async () => {
        let answerFirst;
        api.get
            .mockImplementationOnce(() => new Promise((resolve) => { answerFirst = resolve; }))
            .mockResolvedValueOnce(answer([hit(nodeAt('notes.txt'))]));
        const user = userEvent.setup();
        renderTable();

        await user.type(searchBox(), 'old');
        await waitFor(() => expect(api.get).toHaveBeenCalledTimes(1));
        await searchFor(user, 'er');
        answerFirst(answer([hit(nodeAt('tiny.md')), hit(nodeAt('Big.zip'))]));
        await new Promise((resolve) => setTimeout(resolve, 50));

        expect(api.get.mock.calls[1][1].params.q).toBe('older');
        expect(visibleOrder()).toEqual(['notes.txt']);
    });

    it('sorts the answer by a column on request, folders first, and back to best first', async () => {
        api.get.mockResolvedValue(answer([hit(nodeAt('notes.txt')), hit(nodeAt('apples')), hit(nodeAt('tiny.md'))]));
        const user = userEvent.setup();
        renderTable();

        await searchFor(user, 'e');
        // Best first is no column's order.
        expect(columnHeader('Name')).toHaveAttribute('aria-sort', 'none');

        await user.click(header('Size'));
        expect(visibleOrder()).toEqual(['apples', 'tiny.md', 'notes.txt']);
        expect(columnHeader('Size')).toHaveAttribute('aria-sort', 'ascending');
        expect(screen.getByText('3 matches in this folder and below.')).toBeInTheDocument();

        await user.click(screen.getByRole('button', { name: 'Sort by relevance' }));
        expect(visibleOrder()).toEqual(['notes.txt', 'apples', 'tiny.md']);
        expect(columnHeader('Size')).toHaveAttribute('aria-sort', 'none');
    });

    it('goes back to the folder as it was sorted once the search is cleared', async () => {
        api.get.mockResolvedValue(answer([hit(nodeAt('notes.txt')), hit(nodeAt('tiny.md'))]));
        const user = userEvent.setup();
        renderTable();

        await searchFor(user, 'e');
        await user.click(header('Size'));
        await user.clear(searchBox());

        expect(visibleOrder()).toEqual(['apples', 'Zebra Folder', 'Big.zip', 'notes.txt', 'tiny.md']);
        expect(columnHeader('Name')).toHaveAttribute('aria-sort', 'ascending');
    });

    it('searches again when the files change, so a file deleted meanwhile drops out', async () => {
        api.get
            .mockResolvedValueOnce(answer([hit(nodeAt('notes.txt')), hit(nodeAt('tiny.md'))]))
            .mockResolvedValueOnce(answer([hit(nodeAt('tiny.md'))]));
        const user = userEvent.setup();
        const { rerender } = renderTable();
        await searchFor(user, 'e');
        expect(visibleOrder()).toEqual(['notes.txt', 'tiny.md']);

        // Dashboard hands over a new tree once a delete has gone through.
        rerender(<FileTable files={ROOT_FILES.filter((f) => f.name !== 'notes.txt')} currentPath="" {...noopHandlers} />);

        await waitFor(() => expect(visibleOrder()).toEqual(['tiny.md']));
        expect(api.get).toHaveBeenCalledTimes(2);
        expect(searchBox()).toHaveValue('e');
    });

    it('opens a folder the search found by its full path', async () => {
        api.get.mockResolvedValue(answer([hit(nodeAt('Zebra Folder/nested'))]));
        const user = userEvent.setup();
        const onFolderClick = vi.fn();
        renderTable({ onFolderClick });

        await searchFor(user, 'nested');
        await user.click(screen.getByRole('button', { name: 'nested' }));

        expect(onFolderClick).toHaveBeenCalledWith('Zebra Folder/nested');
    });

    it('draws a focus indicator on every control while searching', async () => {
        api.get.mockResolvedValue(answer([hit(nodeAt('notes.txt')), hit(nodeAt('apples'))]));
        const user = userEvent.setup();
        const { container } = renderTable();

        await searchFor(user, 'e');
        await user.click(header('Size'));

        expect(controlsWithoutFocusIndicator(container)).toEqual([]);
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

        // 2025-12-31 sorts before 2026-01-05, so the column is ordered chronologically
        // rather than by the formatted text the cell displays
        expect(visibleOrder()).toEqual(['apples', 'Zebra Folder', 'tiny.md', 'Big.zip', 'notes.txt']);
    });

    it('orders instants correctly when only some carry fractional seconds', async () => {
        const user = userEvent.setup();
        // The backend drops the fraction when an instant lands exactly on a second, so a list
        // mixes both shapes. A lexical compare sorts "." before "Z" and would flip these.
        const mixed = [
            { name: 'later.txt', isDirectory: false, size: 1, lastModified: '2026-05-01T12:00:00.500Z', relativePath: 'later.txt' },
            { name: 'earlier.txt', isDirectory: false, size: 1, lastModified: '2026-05-01T12:00:00Z', relativePath: 'earlier.txt' },
        ];
        render(<FileTable files={mixed} currentPath="" {...noopHandlers} />);

        await user.click(header('Last Modified'));

        expect(visibleOrder()).toEqual(['earlier.txt', 'later.txt']);
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
        api.get.mockResolvedValue(answer([hit(nodeAt('apples'))]));
        const user = userEvent.setup();
        const { rerender } = renderTable();

        await searchFor(user, 'apple');
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

describe('FileTable row actions', () => {
    // A row renders the filename and its relativePath, so a name can appear twice in one row.
    const rowFor = (name) =>
        screen.getAllByRole('row').find((row) => within(row).queryAllByText(name).length > 0);

    const actionButton = (name, action) =>
        within(rowFor(name)).getByRole('button', { name: `${action} ${name}` });

    it.each([
        ['onDownload', 'Download'],
        ['onShare', 'Share'],
        ['onDelete', 'Delete'],
    ])('passes the clicked file to %s', async (handlerName, action) => {
        const user = userEvent.setup();
        const handler = vi.fn();
        renderTable({ [handlerName]: handler });

        await user.click(actionButton('notes.txt', action));

        expect(handler).toHaveBeenCalledTimes(1);
        expect(handler).toHaveBeenCalledWith(ROOT_FILES.find((f) => f.name === 'notes.txt'));
    });

    it('passes the row that was clicked, not the first row in the table', async () => {
        const user = userEvent.setup();
        const onDownload = vi.fn();
        renderTable({ onDownload });

        await user.click(actionButton('Big.zip', 'Download'));

        expect(onDownload).toHaveBeenCalledWith(ROOT_FILES.find((f) => f.name === 'Big.zip'));
    });

    // Folder rows navigate on click, and the action buttons sit inside that row. Without
    // stopPropagation every share would also open the folder underneath it.
    it.each([
        ['onDownload', 'Download'],
        ['onShare', 'Share'],
        ['onDelete', 'Delete'],
    ])('does not navigate into a folder when %s is clicked on its row', async (handlerName, action) => {
        const user = userEvent.setup();
        const handler = vi.fn();
        const onFolderClick = vi.fn();
        renderTable({ [handlerName]: handler, onFolderClick });

        await user.click(actionButton('apples', action));

        expect(handler).toHaveBeenCalledWith(ROOT_FILES.find((f) => f.name === 'apples'));
        expect(onFolderClick).not.toHaveBeenCalled();
    });

    it('acts on the nested file a search surfaced, not a same-named row in this folder', async () => {
        api.get.mockResolvedValue(answer([hit(nodeAt('Zebra Folder/nested/buried.log'))]));
        const user = userEvent.setup();
        const onDelete = vi.fn();
        renderTable({ onDelete });

        await searchFor(user, 'buried');
        await user.click(actionButton('buried.log', 'Delete'));

        expect(onDelete).toHaveBeenCalledWith(
            expect.objectContaining({ relativePath: 'Zebra Folder/nested/buried.log' }),
        );
    });

    it('does not treat a click on a file row as folder navigation', async () => {
        const user = userEvent.setup();
        const onFolderClick = vi.fn();
        renderTable({ onFolderClick });

        await user.click(rowFor('notes.txt'));

        expect(onFolderClick).not.toHaveBeenCalled();
    });
});

describe('FileTable action names', () => {
    it('says which file each action is for', () => {
        const files = [
            { name: 'a.txt', isDirectory: false, size: 1, lastModified: '2026-01-01T00:00:00Z', relativePath: 'a.txt', id: 1 },
            { name: 'b.txt', isDirectory: false, size: 1, lastModified: '2026-01-01T00:00:00Z', relativePath: 'b.txt', id: 2 },
        ];
        render(<FileTable files={files} currentPath="" {...noopHandlers} onVersions={vi.fn()} />);

        for (const name of ['a.txt', 'b.txt']) {
            for (const label of [`Download ${name}`, `Share ${name}`, `Delete ${name}`, `Versions of ${name}`]) {
                expect(screen.getByRole('button', { name: label })).toBeInTheDocument();
            }
        }
    });
});

describe('FileTable versions action', () => {
    it('offers version history only for tracked files', async () => {
        const user = userEvent.setup();
        const onVersions = vi.fn();
        const tracked = { name: 'kept.txt', isDirectory: false, size: 1, lastModified: '2026-01-01T00:00:00Z', relativePath: 'kept.txt', id: 4 };
        const untracked = { name: 'loose.txt', isDirectory: false, size: 1, lastModified: '2026-01-01T00:00:00Z', relativePath: 'loose.txt' };
        render(<FileTable files={[tracked, untracked]} currentPath="" {...noopHandlers} onVersions={onVersions} />);

        expect(screen.getAllByRole('button', { name: /^Versions of/ })).toHaveLength(1);
        await user.click(screen.getByRole('button', { name: 'Versions of kept.txt' }));
        expect(onVersions).toHaveBeenCalledWith(tracked);
    });

    it('no longer claims an access level it does not track', () => {
        renderTable();

        expect(screen.queryByText('Only You')).not.toBeInTheDocument();
        expect(screen.queryByRole('columnheader', { name: /Access/i })).not.toBeInTheDocument();
    });
});

// jsdom has no layout engine, so these check the classes that decide it.
describe('FileTable on narrow touch screens', () => {
    const classesOf = (element) => element.className.split(/\s+/);

    it('scrolls the table sideways instead of clipping the actions column', () => {
        renderTable();
        const wrapper = screen.getByRole('table').parentElement;

        expect(classesOf(wrapper)).not.toContain('overflow-hidden');
        expect(classesOf(wrapper)).toContain('overflow-x-auto');
    });

    it('hides the actions until hover only on devices that can hover', () => {
        renderTable();
        const actionsCell = within(screen.getAllByRole('row')[1]).getAllByRole('cell').at(-1);

        // A bare opacity-0 would apply on phones too, where nothing ever hovers.
        expect(classesOf(actionsCell)).not.toContain('opacity-0');
        expect(classesOf(actionsCell)).toContain('[@media(hover:hover)]:opacity-0');
    });
});

describe('FileTable keyboard focus', () => {
    it('shows where focus is on every control, folder names included', () => {
        const { container } = renderTable();
        expect(controlsWithoutFocusIndicator(container)).toEqual([]);
    });
});

describe('FileTable empty state', () => {
    it('reports an empty folder rather than rendering a headerless table', () => {
        render(<FileTable files={[]} currentPath="" {...noopHandlers} />);

        expect(screen.getByText('No files found.')).toBeInTheDocument();
        expect(screen.queryByRole('table')).not.toBeInTheDocument();
    });
});

describe('FileTable previews', () => {
    const FILES = [
        { name: 'Photos', isDirectory: true, size: 0, lastModified: '2026-01-01T00:00:00Z', relativePath: 'Photos', children: [] },
        { name: 'notes.txt', isDirectory: false, size: 5, lastModified: '2026-01-01T00:00:00Z', relativePath: 'notes.txt', previewType: 'text' },
        { name: 'Big.zip', isDirectory: false, size: 5, lastModified: '2026-01-01T00:00:00Z', relativePath: 'Big.zip', previewType: null },
    ];

    it("opens a previewable file's preview from its name", async () => {
        const user = userEvent.setup();
        const onPreview = vi.fn();
        render(<FileTable files={FILES} {...noopHandlers} onPreview={onPreview} />);

        await user.click(screen.getByRole('button', { name: 'Preview notes.txt' }));

        expect(onPreview).toHaveBeenCalledWith(FILES[1]);
        expect(noopHandlers.onFolderClick).not.toHaveBeenCalled();
    });

    it('leaves files the server cannot preview, and folders, as they were', () => {
        render(<FileTable files={FILES} {...noopHandlers} onPreview={vi.fn()} />);

        expect(screen.queryByRole('button', { name: 'Preview Big.zip' })).not.toBeInTheDocument();
        expect(screen.queryByRole('button', { name: 'Preview Photos' })).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Photos' })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Download Big.zip' })).toBeInTheDocument();
    });

    it('offers no preview when the table is given nowhere to show one', () => {
        render(<FileTable files={FILES} {...noopHandlers} />);

        expect(screen.queryByRole('button', { name: 'Preview notes.txt' })).not.toBeInTheDocument();
        expect(visibleOrder()).toContain('notes.txt');
    });

    it('draws a focus indicator on the preview button', () => {
        const { container } = render(<FileTable files={FILES} {...noopHandlers} onPreview={vi.fn()} />);

        expect(controlsWithoutFocusIndicator(container)).toEqual([]);
    });
});
