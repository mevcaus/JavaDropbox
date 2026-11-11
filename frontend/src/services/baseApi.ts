import { createApi } from '@reduxjs/toolkit/query/react';
import { baseQuery } from './baseQuery';

/**
 * The RTK Query slice everything the app reads from or changes on the server goes through. Its
 * endpoints are generated from the API spec into generatedApi.ts (npm run api:client), and
 * filesApi.ts adds the cache tags and the requests the spec can't describe.
 */
export const baseApi = createApi({
    reducerPath: 'api',
    baseQuery,
    // One tag for the whole file tree, which comes back as one answer. Once it's listed per folder
    // (#228), each folder can have its own, so a change refetches only the folders it touched.
    tagTypes: ['Files'],
    endpoints: () => ({}),
});
