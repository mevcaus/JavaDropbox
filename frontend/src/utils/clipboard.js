// The Clipboard API only exists in secure contexts, and a self-hosted install is often opened over
// plain http on a LAN address, where navigator.clipboard is undefined. Fall back to selecting the
// text in its input and the legacy copy command, and report whether anything worked.
export const copyToClipboard = async (text, input) => {
    if (navigator.clipboard?.writeText) {
        try {
            await navigator.clipboard.writeText(text);
            return true;
        } catch {
            // Permission denied or the document lost focus; try the fallback.
        }
    }
    input?.focus();
    input?.select();
    try {
        return document.execCommand?.('copy') ?? false;
    } catch {
        return false;
    }
};
