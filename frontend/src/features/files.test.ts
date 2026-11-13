import { describe, expect, it } from 'vitest';
import { filesByFolder, filesIn, totalSize } from './files';
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
