// The backend does not always fail with a JSON body -- an unreachable database, for instance,
// produces Tomcat's HTML error page, stack trace and all. Rendering a response body straight into
// the UI would put that on screen, so pick out something a person can actually read.
const isHtmlDocument = (value) => /^\s*<(!doctype|html)/i.test(value);

export const readableError = (error, fallback) => {
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
        return 'Invalid username or password.';
    }
    if (status >= 500) {
        return 'The server is unavailable right now. Please try again.';
    }
    return fallback;
};
