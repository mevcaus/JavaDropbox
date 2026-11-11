import type { BaseQueryFn } from '@reduxjs/toolkit/query';
import type { AxiosError } from 'axios';
import api from './api';
import { REQUEST_BODIES, type BodyType } from './requestBodies';

/** What an endpoint asks the base query to send, as the generated endpoints describe it. */
export type Request = {
    url: string;
    method?: string;
    params?: Record<string, unknown>;
    body?: Record<string, unknown>;
    /** How to send the body, for a request no generated endpoint describes. */
    bodyType?: BodyType;
};

/** A failed request: the HTTP status and the body the server answered with. */
export type RequestError = {
    /** The HTTP status, or FETCH_ERROR when no answer came back at all. */
    status: number | 'FETCH_ERROR';
    data?: unknown;
};

// A file goes in under its own bare name. For a file from the folder picker, the browser would
// otherwise send its webkitRelativePath ("Photos/2024/beach.jpg"), and the server takes only a
// bare name.
const append = (form: FormData | URLSearchParams, name: string, value: unknown) => {
    if (value === undefined || value === null) return;
    if (form instanceof FormData && value instanceof File) {
        form.append(name, value, value.name);
    } else if (form instanceof FormData && value instanceof Blob) {
        form.append(name, value);
    } else {
        form.append(name, String(value));
    }
};

// The server takes forms and uploads, not JSON: a form endpoint answers JSON with a 415 (#371).
const encode = (body: Record<string, unknown>, type: BodyType): FormData | URLSearchParams => {
    const form = type === 'multipart' ? new FormData() : new URLSearchParams();
    for (const [name, value] of Object.entries(body)) {
        for (const item of Array.isArray(value) ? value : [value]) {
            append(form, name, item);
        }
    }
    return form;
};

/**
 * Sends the API slice's requests through the app's axios client, so they keep its CSRF priming and
 * its sign-out on a 401 (see api.js), and sends each body the way its endpoint takes it.
 */
export const baseQuery: BaseQueryFn<Request, unknown, RequestError> = async (request, { endpoint }) => {
    const type = request.bodyType ?? REQUEST_BODIES[endpoint];
    if (request.body !== undefined && type === undefined) {
        // Every endpoint with a body takes a form or an upload, so this is a mistake in the slice.
        throw new Error(`${endpoint} has a body, but the spec doesn't say how to send it`);
    }
    try {
        const data = request.body === undefined || type === undefined ? undefined : encode(request.body, type);
        const config = { params: request.params };
        // By method, as the rest of the app calls the client (and as its tests mock it).
        const method = (request.method ?? 'GET').toUpperCase();
        const response =
            method === 'GET'
                ? await api.get(request.url, config)
                : method === 'DELETE'
                  ? await api.delete(request.url, config)
                  : method === 'PUT'
                    ? await api.put(request.url, data, config)
                    : await api.post(request.url, data, config);
        return { data: response.data };
    } catch (failure) {
        const error = failure as AxiosError;
        if (!error.response) {
            return { error: { status: 'FETCH_ERROR' } };
        }
        return { error: { status: error.response.status, data: error.response.data } };
    }
};
