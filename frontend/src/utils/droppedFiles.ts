import { startsWithDot } from './names';
import type { PickedFile } from '../services/apiSlice';

// The parts of the browser's drag and drop that a drop is read through. A DataTransfer fits them,
// and so do the stand-ins the tests make, since jsdom has none of its own.

/** A file or folder in a drop, as FileSystemEntry describes it. */
export interface DroppedEntry {
    name: string;
    isFile: boolean;
    isDirectory: boolean;
}

interface DroppedFileEntry extends DroppedEntry {
    file(success: (file: File) => void, failure?: (error: DOMException) => void): void;
}

interface DroppedFolderEntry extends DroppedEntry {
    createReader(): {
        readEntries(success: (entries: DroppedEntry[]) => void, failure?: (error: DOMException) => void): void;
    };
}

/** One thing dropped, as DataTransferItem describes it. */
export interface DroppedItem {
    kind: string;
    webkitGetAsEntry?(): DroppedEntry | null;
    getAsFile(): File | null;
}

// An entry says which it is (FileSystemFileEntry or FileSystemDirectoryEntry) by isFile and
// isDirectory.
const isFileEntry = (entry: DroppedEntry): entry is DroppedFileEntry => entry.isFile;
const isFolderEntry = (entry: DroppedEntry): entry is DroppedFolderEntry => entry.isDirectory;

/** Whether a drag carries files from the computer, as opposed to text or a link. */
export const carriesFiles = (dataTransfer: { types?: ArrayLike<string> | null } | null | undefined): boolean =>
    Array.from(dataTransfer?.types ?? []).includes('Files');

// A folder's reader hands its entries over in batches (Chrome gives 100 at a time) and an empty
// batch means it is done.
const readAll = async (reader: ReturnType<DroppedFolderEntry['createReader']>): Promise<DroppedEntry[]> => {
    const entries: DroppedEntry[] = [];
    for (;;) {
        const batch = await new Promise<DroppedEntry[]>((resolve, reject) => reader.readEntries(resolve, reject));
        if (batch.length === 0) return entries;
        entries.push(...batch);
    }
};

const fileOf = (entry: DroppedFileEntry): Promise<File> => new Promise((resolve, reject) => entry.file(resolve, reject));

// The most reads of a drop under way at once. Enough to keep a large drop moving, few enough that
// a drop of thousands of files doesn't ask the browser for all of them at the same moment, which
// could make reads fail that would otherwise work.
const READS_AT_ONCE = 16;

// Runs read when one of READS_AT_ONCE slots is free. Only the reads themselves take a slot, not
// the walk around them, so a folder waiting on its children never holds one.
const readSlots = () => {
    let free = READS_AT_ONCE;
    const waiting: (() => void)[] = [];
    return async <T>(read: () => Promise<T>): Promise<T> => {
        if (free > 0) free -= 1;
        else await new Promise<void>((resolve) => waiting.push(resolve));
        try {
            return await read();
        } finally {
            const next = waiting.shift();
            if (next) next();
            else free += 1;
        }
    };
};

/**
 * The files dropped on the page, each with its path inside what was dropped: "notes.txt" for a
 * loose file, "Trip/Day 1/a.jpg" for one inside a dropped folder. That is the shape uploadFolder
 * takes, so a drop and the folder picker upload the same way.
 *
 * Dot-named files and folders are left out and named in skipped, each name once, sorted. A dot-named
 * folder is not walked into at all: .git or node_modules/.cache can hold thousands of files.
 * Empty folders are not reported, as with the folder picker.
 *
 * A file or folder the browser can't read, such as a broken link or a file another program has
 * locked, is left out too, and its path inside the drop is named in unreadable. Everything else
 * is still uploaded.
 *
 * The folders are read side by side, up to READS_AT_ONCE reads at a time, which a large drop
 * needs, and the files come back in the order the browser listed them.
 *
 * Reads the drop's items before the first await, since the browser empties them once the drop
 * event has been handled.
 */
export const readDrop = async (dataTransfer: {
    items?: ArrayLike<DroppedItem> | null;
}): Promise<{ files: PickedFile[]; skipped: string[]; unreadable: string[] }> => {
    const items = Array.from(dataTransfer.items ?? []).filter((item) => item.kind === 'file');
    // A browser without entries, or an item that has none, still hands over the file itself.
    const roots = items.map((item) => item.webkitGetAsEntry?.() ?? item.getAsFile());

    const skipped = new Set<string>();
    const unreadable: string[] = [];
    const inSlot = readSlots();

    const walk = async (entry: DroppedEntry | File, folder: string): Promise<PickedFile[]> => {
        if (startsWithDot(entry.name)) {
            skipped.add(entry.name);
            return [];
        }
        const relativePath = folder ? `${folder}/${entry.name}` : entry.name;
        try {
            if (entry instanceof File) {
                return [{ file: entry, relativePath }];
            }
            if (isFileEntry(entry)) {
                return [{ file: await inSlot(() => fileOf(entry)), relativePath }];
            }
            if (isFolderEntry(entry)) {
                const children = await inSlot(() => readAll(entry.createReader()));
                return (await Promise.all(children.map((child) => walk(child, relativePath)))).flat();
            }
        } catch {
            // The browser says only that it failed (NotFoundError, NotReadableError), so the
            // path is all there is to tell.
            unreadable.push(relativePath);
        }
        return [];
    };

    const found = await Promise.all(roots.map((root) => (root ? walk(root, '') : [])));
    // Sorted, since the folders finish in whatever order they do.
    return { files: found.flat(), skipped: [...skipped].sort(), unreadable: unreadable.sort() };
};
