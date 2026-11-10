import { describe, expect, it } from 'vitest';
import { carriesFiles, readDrop } from './droppedFiles';

// FileSystemEntry stand-ins: jsdom has no drag and drop of its own.
const fileEntry = (name) => ({ name, isFile: true, isDirectory: false, file: (resolve) => resolve(new File(['x'], name)) });

// batchSize mimics Chrome, whose reader hands over at most 100 entries per call.
const folderEntry = (name, children, batchSize = 100) => ({
    name,
    isFile: false,
    isDirectory: true,
    createReader: () => {
        let next = 0;
        return {
            readEntries: (resolve) => {
                const batch = children.slice(next, next + batchSize);
                next += batch.length;
                setTimeout(() => resolve(batch));
            },
        };
    },
});

const droppedItem = (entry) => ({ kind: 'file', webkitGetAsEntry: () => entry, getAsFile: () => null });
const drop = (...items) => ({ types: ['Files'], items });
const paths = ({ files }) => files.map(({ relativePath }) => relativePath);

describe('readDrop', () => {
    it('gives each file its path inside the dropped folder', async () => {
        const result = await readDrop(drop(
            droppedItem(folderEntry('Trip', [fileEntry('plan.txt'), folderEntry('Day 1', [fileEntry('a.jpg')])])),
            droppedItem(fileEntry('notes.txt')),
        ));

        expect(paths(result)).toEqual(['Trip/plan.txt', 'Trip/Day 1/a.jpg', 'notes.txt']);
        expect(result.files[0].file.name).toBe('plan.txt');
        expect(result.skipped).toEqual([]);
    });

    it('reads a folder until its reader runs dry, not just the first batch', async () => {
        const children = Array.from({ length: 5 }, (_, i) => fileEntry(`${i}.txt`));

        const result = await readDrop(drop(droppedItem(folderEntry('Many', children, 2))));

        expect(paths(result)).toEqual(['Many/0.txt', 'Many/1.txt', 'Many/2.txt', 'Many/3.txt', 'Many/4.txt']);
    });

    it('leaves out dot-named files and folders without walking into them, naming each once', async () => {
        const git = folderEntry('.git', []);
        git.createReader = () => {
            throw new Error('walked into .git');
        };

        const result = await readDrop(drop(
            droppedItem(folderEntry('Trip', [fileEntry('.DS_Store'), git, folderEntry('Day 1', [fileEntry('.DS_Store')])])),
            droppedItem(fileEntry('.env')),
        ));

        expect(result.files).toEqual([]);
        expect(result.skipped).toEqual(['.DS_Store', '.git', '.env']);
    });

    it('takes the file itself when an item has no entry', async () => {
        const file = new File(['x'], 'plain.txt');

        const result = await readDrop(drop({ kind: 'file', webkitGetAsEntry: () => null, getAsFile: () => file }));

        expect(result.files).toEqual([{ file, relativePath: 'plain.txt' }]);
    });

    it('ignores items that are not files', async () => {
        const result = await readDrop(drop({ kind: 'string', webkitGetAsEntry: () => null, getAsFile: () => null }));

        expect(result).toEqual({ files: [], skipped: [] });
    });
});

describe('carriesFiles', () => {
    it('is true only for drags that carry files', () => {
        expect(carriesFiles({ types: ['Files'] })).toBe(true);
        expect(carriesFiles({ types: ['text/plain', 'text/uri-list'] })).toBe(false);
        expect(carriesFiles(null)).toBe(false);
    });
});
