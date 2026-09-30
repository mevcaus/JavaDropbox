// The backend does not always fail with a JSON body -- an unreachable database, for instance,
// produces Tomcat's HTML error page, stack trace and all. Rendering a response body straight into
// the UI would put that on screen, so pick out something a person can actually read.
const isHtmlDocument = (value) => /^\s*<(!doctype|html)/i.test(value);

/**
 * A message fit to show for a failed request: the server's own message when it sent one, otherwise
 * one chosen from the status, otherwise the fallback.
 *
 * A 401 reads as an expired session, which is what it means for every request but the sign-in
 * itself; the login form passes its own text for that case in options.unauthorized.
 */
export const readableError = (
    error,
    fallback,
    { unauthorized = 'Your session has expired. Please sign in again.' } = {},
) => {
    const { status, data } = error?.response ?? {};

    if (data && typeof data === 'object') {
        const message = data.error || data.message;
        if (typeof message === 'string' && message.trim()) {
            return message;
        }
    }

    // A genuine message from the API is short and is not a document; anything longer is a dump.
    if (typeof data === 'string' && data.trim() && !isHtmlDocument(data) && data.length <= 200) {
        return data.trim();
    }

    if (status === 401) {
        return unauthorized;
    }
    if (status >= 500) {
        return 'The server is unavailable right now. Please try again.';
    }
    return fallback;
};
