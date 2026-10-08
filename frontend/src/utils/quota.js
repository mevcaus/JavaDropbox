export const QUOTA_UNITS = [
    { label: 'MB', bytes: 1024 ** 2 },
    { label: 'GB', bytes: 1024 ** 3 },
    { label: 'TB', bytes: 1024 ** 4 },
];

const MB = QUOTA_UNITS[0].bytes;

/** What the quota forms say when formToQuota refuses an amount. */
export const QUOTA_ERROR = 'The quota has to be at least 1 MB, or empty for no limit.';

/**
 * A quota in bytes as the amount and unit to show in a form: the largest unit it is a whole number
 * of, so 5 GB reads as 5 GB rather than 5120 MB. No quota is an empty amount.
 */
export const quotaToForm = (bytes) => {
    if (!bytes) return { amount: '', unit: 'GB' };
    const unit = [...QUOTA_UNITS].reverse().find((u) => bytes % u.bytes === 0);
    if (unit) return { amount: String(bytes / unit.bytes), unit: unit.label };
    // Not a whole number of MB, which only the API can set: rounded, rather than shown as a long
    // run of decimals.
    const mb = bytes / MB;
    return { amount: String(mb >= 1 ? Math.round(mb * 100) / 100 : Number(mb.toPrecision(3))), unit: 'MB' };
};

/**
 * The bytes for an amount typed in a unit, as the API takes a quota; an empty amount is no quota.
 * The bytes are rounded to a whole number of MB, so the quota reads back as typed (0.1 GB is
 * 102 MB, not 102.39999961853027 MB). Returns null for an amount that is not a positive number,
 * comes to less than 1 MB, or is too large to send exactly.
 */
export const formToQuota = (amount, unit) => {
    const text = String(amount).trim();
    if (text === '') return '';
    const value = Number(text);
    const unitBytes = QUOTA_UNITS.find((u) => u.label === unit)?.bytes;
    if (!Number.isFinite(value) || value <= 0 || !unitBytes) return null;
    const bytes = Math.round((value * unitBytes) / MB) * MB;
    return bytes > 0 && bytes <= Number.MAX_SAFE_INTEGER ? String(bytes) : null;
};
