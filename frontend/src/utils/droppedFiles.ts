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

/**
 * The files dropped on the page, each with its path inside what was dropped: "notes.txt" for a
 * loose file, "Trip/Day 1/a.jpg" for one inside a dropped folder. That is the shape uploadFolder
 * takes, so a drop and the folder picker upload the same way.
 *
 * Dot-named files and folders are left out and named in skipped, each name once. A dot-named
 * folder is not walked into at all: .git or node_modules/.cache can hold thousands of files.
 * Empty folders are not reported, as with the folder picker.
 *
 * Reads the drop's items before the first await, since the browser empties them once the drop
 * event has been handled.
 */
export const readDrop = async (dataTransfer: {
    items?: ArrayLike<DroppedItem> | null;
}): Promise<{ files: PickedFile[]; skipped: string[] }> => {
    const items = Array.from(dataTransfer.items ?? []).filter((item) => item.kind === 'file');
    // A browser without entries, or an item that has none, still hands over the file itself.
    const roots = items.map((item) => item.webkitGetAsEntry?.() ?? item.getAsFile());

    const files: PickedFile[] = [];
    const skipped = new Set<string>();

    const walk = async (entry: DroppedEntry | File, folder: string): Promise<void> => {
        if (startsWithDot(entry.name)) {
            skipped.add(entry.name);
            return;
        }
        const relativePath = folder ? `${folder}/${entry.name}` : entry.name;
        if (entry instanceof File) {
            files.push({ file: entry, relativePath });
        } else if (isFileEntry(entry)) {
            files.push({ file: await fileOf(entry), relativePath });
        } else if (isFolderEntry(entry)) {
            for (const child of await readAll(entry.createReader())) {
                await walk(child, relativePath);
            }
        }
    };

    for (const root of roots) {
        if (root) await walk(root, '');
    }
    return { files, skipped: [...skipped] };
};
