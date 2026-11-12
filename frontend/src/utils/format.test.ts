import { describe, expect, it } from 'vitest';
import { formatSize } from './format';

describe('formatSize', () => {
    it.each([
        [0, '0 B'],
        [512, '512 B'],
        [2048, '2 KB'],
        [1536, '1.5 KB'],
        [1048576, '1 MB'],
        [5 * 1024 ** 3, '5 GB'],
        [3 * 1024 ** 5, '3 PB'],
        [2 * 1024 ** 6, '2048 PB'],
    ])('formats %s bytes as %s', (bytes, expected) => {
        expect(formatSize(bytes)).toBe(expected);
    });

    it.each([undefined, null, -1, Number.NaN, '12'])('renders %s as a dash', (value) => {
        expect(formatSize(value)).toBe('—');
    });
});
