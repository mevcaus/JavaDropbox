import { describe, expect, it } from 'vitest';
import { filesByFolder, filesIn, largestUpload, totalSize, uploadBatches } from './files';
import type { FileTreeNode } from '../services/apiSlice';

// An item of the tree as getFileTree answers it, with only what these tests read chosen.
const node = (name: string, size: number, children?: FileTreeNode[]): FileTreeNode => ({
    name,
    isDirectory: children !== undefined,
    size,
    children: children ?? [],
    createdDate: null,
    id: null,
    lastModified: null,
    ownerName: 'ada',
    previewType: null,
    relativePath: name,
});

describe('filesIn', () => {
    const tree = [node('docs', 0, [node('a.txt', 3)]), node('b.txt', 4)];

    it('finds the items in a nested folder', () => {
        expect(filesIn(tree, 'docs')).toBe(tree[0].children);
        expect(filesIn(tree, '')).toBe(tree);
    });

    // A component comparing by reference would otherwise see a change every time.
    it('gives the same empty list for a folder that is not there, or no tree yet', () => {
        expect(filesIn(tree, 'missing')).toBe(filesIn(undefined, ''));
        expect(filesIn(tree, 'b.txt')).toEqual([]);
    });

    it('adds up the sizes at the top of the tree', () => {
        expect(totalSize(tree)).toBe(4);
        expect(totalSize(undefined)).toBe(0);
    });
});

describe('uploadBatches', () => {
    const file = (name: string, size: number) => new File([new Uint8Array(size)], name);
    const names = (batches: File[][]) => batches.map((batch) => batch.map(({ name }) => name));
    const NO_LIMITS = { maxFileBytes: null, maxRequestBytes: null, maxFilesPerRequest: null };

    it('sends everything at once when nothing is limited', () => {
        const files = Array.from({ length: 200 }, (_, i) => file(`${i}`, 10));

        expect(uploadBatches(files, 'Photos', NO_LIMITS)).toEqual([files]);
    });

    it('puts no more files in a request than the server takes, keeping their order', () => {
        const files = Array.from({ length: 200 }, (_, i) => file(`${i}`, 10));

        const batches = uploadBatches(files, '', { ...NO_LIMITS, maxFilesPerRequest: 49 });

        expect(batches.map((batch) => batch.length)).toEqual([49, 49, 49, 49, 4]);
        expect(batches.flat()).toEqual(files);
    });

    it('keeps each request under the size the server takes, framing included', () => {
        // 3000 bytes each, about 4000 with the framing: two fit in 10,000, three don't.
        const files = ['a', 'b', 'c', 'd', 'e'].map((name) => file(name, 3000));

        const batches = uploadBatches(files, '', { ...NO_LIMITS, maxRequestBytes: 10_000 });

        expect(names(batches)).toEqual([['a', 'b'], ['c', 'd'], ['e']]);
    });

    it('counts the path toward the request', () => {
        const files = ['a', 'b'].map((name) => file(name, 3000));
        // Two would fit alongside a short path (about 9100 bytes), not alongside this one.
        const path = 'x'.repeat(1000);

        expect(names(uploadBatches(files, '', { ...NO_LIMITS, maxRequestBytes: 9500 }))).toEqual([['a', 'b']]);
        expect(names(uploadBatches(files, path, { ...NO_LIMITS, maxRequestBytes: 9500 }))).toEqual([['a'], ['b']]);
    });

    it('still sends a file too large for any request, alone, for the server to refuse', () => {
        const files = [file('small', 10), file('huge', 50_000), file('after', 10)];

        expect(names(uploadBatches(files, '', { ...NO_LIMITS, maxRequestBytes: 10_000 }))).toEqual([
            ['small'],
            ['huge'],
            ['after'],
        ]);
    });

    it('takes the largest file to be the smaller of the file limit and a request less its framing', () => {
        expect(largestUpload(NO_LIMITS)).toBe(Infinity);
        expect(largestUpload({ ...NO_LIMITS, maxFileBytes: 5000 })).toBe(5000);
        expect(largestUpload({ ...NO_LIMITS, maxRequestBytes: 10_000 })).toBe(10_000 - 2048);
        expect(largestUpload({ maxFileBytes: 9000, maxRequestBytes: 10_000, maxFilesPerRequest: 49 })).toBe(10_000 - 2048);
    });

    it('sends nothing for no files', () => {
        expect(uploadBatches([], '', { ...NO_LIMITS, maxFilesPerRequest: 49 })).toEqual([]);
    });
});

describe('filesByFolder', () => {
    const picked = (relativePath: string) => ({
        file: new File(['x'], relativePath.slice(relativePath.lastIndexOf('/') + 1)),
        relativePath,
    });

    it('groups files by the folder each goes into below the open one, parents first', () => {
        const groups = filesByFolder(
            [picked('Trip/Day 1/a.jpg'), picked('Trip/plan.txt'), picked('Trip/Day 1/b.jpg')],
            'Holidays',
        );

        expect(groups.map(([folder, files]) => [folder, files.map((file) => file.name)])).toEqual([
            ['Holidays/Trip', ['plan.txt']],
            ['Holidays/Trip/Day 1', ['a.jpg', 'b.jpg']],
        ]);
    });
});
