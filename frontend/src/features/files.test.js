import { describe, expect, it } from 'vitest';
import { filesByFolder, filesIn, totalSize } from './files';

describe('filesIn', () => {
    const tree = [
        { name: 'docs', isDirectory: true, size: 0, children: [{ name: 'a.txt', isDirectory: false, size: 3 }] },
        { name: 'b.txt', isDirectory: false, size: 4 },
    ];

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

describe('filesByFolder', () => {
    const picked = (relativePath) => ({ file: new File(['x'], relativePath.split('/').at(-1)), relativePath });

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
