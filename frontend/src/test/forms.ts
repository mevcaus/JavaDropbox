// What a request posted, checked to be the kind of body expected before a test reads it.

/** A form a request posted (application/x-www-form-urlencoded), checked to be one. */
export const postedFormBody = (body: unknown): URLSearchParams => {
    if (!(body instanceof URLSearchParams)) throw new Error('The request posted no form');
    return body;
};

/** The fields of a form a request posted, for a test to compare. */
export const postedForm = (body: unknown): Record<string, string> => Object.fromEntries(postedFormBody(body));

/** A multipart body a request posted, checked to be one. */
export const postedMultipart = (body: unknown): FormData => {
    if (!(body instanceof FormData)) throw new Error('The request posted no multipart body');
    return body;
};

/** The names of the files a multipart body carried in its `files` parts, each checked to be a file. */
export const postedFileNames = (body: unknown): string[] =>
    postedMultipart(body)
        .getAll('files')
        .map((part) => {
            if (!(part instanceof File)) throw new Error('A files part is not a file');
            return part.name;
        });
