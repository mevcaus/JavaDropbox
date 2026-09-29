const UNITS = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];

// Sizes arrive as byte counts from the API. Anything that is not a sensible count renders as a
// dash rather than "NaN undefined".
export const formatSize = (bytes) => {
    if (typeof bytes !== 'number' || !Number.isFinite(bytes) || bytes < 0) return '—';
    if (bytes === 0) return '0 B';
    const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), UNITS.length - 1);
    return `${parseFloat((bytes / 1024 ** exponent).toFixed(2))} ${UNITS[exponent]}`;
};
