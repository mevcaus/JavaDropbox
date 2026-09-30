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

    // The API's own errors carry a specific `message`; Spring's default error body adds only the
    // generic status text in `error` ("Bad Request"), which is the last resort.
    if (data && typeof data === 'object') {
        const message = [data.message, data.error].find((value) => typeof value === 'string' && value.trim());
        if (message) {
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
