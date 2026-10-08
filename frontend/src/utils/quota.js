export const QUOTA_UNITS = [
    { label: 'MB', bytes: 1024 ** 2 },
    { label: 'GB', bytes: 1024 ** 3 },
    { label: 'TB', bytes: 1024 ** 4 },
];

/**
 * A quota in bytes as the amount and unit to show in a form: the largest unit it is a whole number
 * of, so 5 GB reads as 5 GB rather than 5120 MB. No quota is an empty amount.
 */
export const quotaToForm = (bytes) => {
    if (!bytes) return { amount: '', unit: 'GB' };
    const unit = [...QUOTA_UNITS].reverse().find((u) => bytes % u.bytes === 0) ?? QUOTA_UNITS[0];
    return { amount: String(bytes / unit.bytes), unit: unit.label };
};

/**
 * The bytes for an amount typed in a unit, as the API takes a quota; an empty amount is no quota.
 * Returns null for an amount that is not a positive number.
 */
export const formToQuota = (amount, unit) => {
    const text = String(amount).trim();
    if (text === '') return '';
    const value = Number(text);
    const unitBytes = QUOTA_UNITS.find((u) => u.label === unit)?.bytes;
    if (!Number.isFinite(value) || value <= 0 || !unitBytes) return null;
    return String(Math.round(value * unitBytes));
};
