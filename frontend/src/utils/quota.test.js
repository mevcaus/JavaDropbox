import { describe, expect, it } from 'vitest';
import { formToQuota, quotaToForm } from './quota';

describe('quota', () => {
    it('shows a quota in the largest unit it is a whole number of', () => {
        expect(quotaToForm(5 * 1024 ** 3)).toEqual({ amount: '5', unit: 'GB' });
        expect(quotaToForm(1536 * 1024 ** 2)).toEqual({ amount: '1536', unit: 'MB' });
        expect(quotaToForm(2 * 1024 ** 4)).toEqual({ amount: '2', unit: 'TB' });
        expect(quotaToForm(null)).toEqual({ amount: '', unit: 'GB' });
    });

    it('turns an amount and unit into bytes, and an empty amount into no quota', () => {
        expect(formToQuota('1.5', 'GB')).toBe(String(1.5 * 1024 ** 3));
        expect(formToQuota(' 500 ', 'MB')).toBe(String(500 * 1024 ** 2));
        expect(formToQuota('', 'GB')).toBe('');
    });

    it('refuses an amount that is not a positive number', () => {
        expect(formToQuota('0', 'GB')).toBeNull();
        expect(formToQuota('-1', 'GB')).toBeNull();
        expect(formToQuota('lots', 'GB')).toBeNull();
        expect(formToQuota('5', 'PB')).toBeNull();
    });

    it('rounds to a whole number of MB, so a quota reads back as typed', () => {
        const bytes = formToQuota('0.1', 'GB');
        expect(bytes).toBe(String(102 * 1024 ** 2));
        expect(quotaToForm(Number(bytes))).toEqual({ amount: '102', unit: 'MB' });
    });

    it('refuses amounts that round to nothing or are too large to send exactly', () => {
        expect(formToQuota('0.0000001', 'MB')).toBeNull();
        expect(formToQuota('10000000', 'TB')).toBeNull();
        expect(formToQuota('1e9', 'TB')).toBeNull();
    });

    it('shows a quota that is not a whole number of MB rounded, not as a long decimal', () => {
        expect(quotaToForm(107374182)).toEqual({ amount: '102.4', unit: 'MB' });
        expect(quotaToForm(1500)).toEqual({ amount: '0.00143', unit: 'MB' });
    });
});
