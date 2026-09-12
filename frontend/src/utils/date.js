const formatter = new Intl.DateTimeFormat(undefined, {
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
});

// The backend sends absolute ISO-8601 instants (e.g. "2026-09-12T16:00:00.123Z"), so the
// viewer's own locale and timezone decide how they read.
export const formatDate = (isoString) => {
    if (!isoString) return '-';
    try {
        return formatter.format(new Date(isoString));
    } catch {
        return isoString;
    }
};

// Sort key for an ISO-8601 instant. These have to be parsed rather than compared as raw
// strings: the backend omits the fractional seconds when an instant lands exactly on a
// second, and "." sorts before "Z", so a lexical compare would place the later
// "...:00.500Z" ahead of the earlier "...:00Z".
export const toTimestamp = (isoString) => {
    const parsed = Date.parse(isoString);
    return Number.isNaN(parsed) ? 0 : parsed;
};
