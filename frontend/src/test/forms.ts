/** The fields of a form a request posted, checked to be one, for a test to compare. */
export const postedForm = (body: unknown): Record<string, string> => {
    if (!(body instanceof URLSearchParams)) throw new Error('The request posted no form');
    return Object.fromEntries(body);
};

/** A multipart body a request posted, checked to be one. */
export const postedMultipart = (body: unknown): FormData => {
    if (!(body instanceof FormData)) throw new Error('The request posted no multipart body');
    return body;
};

/** The names of the files a multipart body carried in its `files` parts. */
export const postedFileNames = (body: unknown): string[] =>
    postedMultipart(body)
        .getAll('files')
        .map((part) => (part instanceof File ? part.name : String(part)));
