// The backend does not always fail with a JSON body -- an unreachable database, for instance,
// produces Tomcat's HTML error page, stack trace and all. Rendering a response body straight into
// the UI would put that on screen, so pick out something a person can actually read.
const isHtmlDocument = (value: string): boolean => /^\s*<(!doctype|html)/i.test(value);

// The fields of something that may or may not be an object, such as an error or the body of an
// answer; none when it isn't one.
export const fieldsOf = (value: unknown): Record<string, unknown> =>
    typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {};

/** The HTTP status an axios request failed with, if it got an answer. */
export const statusOf = (error: unknown): number | undefined => {
    const { status } = fieldsOf(fieldsOf(error).response);
    return typeof status === 'number' ? status : undefined;
};

/**
 * A message fit to show for a failed request: the server's own message when it sent one, otherwise
 * one chosen from the status, otherwise the fallback.
 *
 * A 401 reads as an expired session, which is what it means for every request but the sign-in
 * itself; the login form passes its own text for that case in options.unauthorized.
 */
export const readableError = (
    error: unknown,
    fallback: string,
    { unauthorized = 'Your session has expired. Please sign in again.' }: { unauthorized?: string } = {},
): string => {
    // An axios error carries the answer in error.response; the API slice's errors are the answer.
    const { status, data } = fieldsOf(fieldsOf(error).response ?? error);

    // The API's own errors carry a specific `message`; Spring's default error body adds only the
    // generic status text in `error` ("Bad Request"), which is the last resort.
    if (data && typeof data === 'object') {
        const { message: specific, error: generic } = fieldsOf(data);
        const message = [specific, generic].find(
            (value): value is string => typeof value === 'string' && value.trim() !== '',
        );
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
    if (typeof status === 'number' && status >= 500) {
        return 'The server is unavailable right now. Please try again.';
    }
    return fallback;
};
