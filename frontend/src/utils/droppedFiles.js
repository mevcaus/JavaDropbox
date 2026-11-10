import { startsWithDot } from './names';

/** Whether a drag carries files from the computer, as opposed to text or a link. */
export const carriesFiles = (dataTransfer) => Array.from(dataTransfer?.types ?? []).includes('Files');

// A folder's reader hands its entries over in batches (Chrome gives 100 at a time) and an empty
// batch means it is done.
const readAll = async (reader) => {
    const entries = [];
    for (;;) {
        const batch = await new Promise((resolve, reject) => reader.readEntries(resolve, reject));
        if (batch.length === 0) return entries;
        entries.push(...batch);
    }
};

const fileOf = (entry) => new Promise((resolve, reject) => entry.file(resolve, reject));

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
export const readDrop = async (dataTransfer) => {
    const items = Array.from(dataTransfer.items ?? []).filter((item) => item.kind === 'file');
    // A browser without entries, or an item that has none, still hands over the file itself.
    const roots = items.map((item) => item.webkitGetAsEntry?.() ?? item.getAsFile());

    const files = [];
    const skipped = new Set();

    const walk = async (entry, folder) => {
        if (startsWithDot(entry.name)) {
            skipped.add(entry.name);
            return;
        }
        const relativePath = folder ? `${folder}/${entry.name}` : entry.name;
        if (entry instanceof File) {
            files.push({ file: entry, relativePath });
        } else if (entry.isFile) {
            files.push({ file: await fileOf(entry), relativePath });
        } else if (entry.isDirectory) {
            for (const child of await readAll(entry.createReader())) {
                await walk(child, relativePath);
            }
        }
    };

    for (const root of roots.filter(Boolean)) {
        await walk(root, '');
    }
    return { files, skipped: [...skipped] };
};
