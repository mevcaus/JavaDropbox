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
    });
});
