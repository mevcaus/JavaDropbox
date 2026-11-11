// Generates src/services/generatedApi.ts, the RTK Query endpoints, from the API spec
// (npm run api:client). The GETs that return a file are left out: the app links to them
// rather than loading them into the store.
const FILES = new Set(['downloadItem', 'previewFile', 'openSharedItem', 'downloadSharedItem', 'previewSharedFile']);

/** @type {import('@rtk-query/codegen-openapi').ConfigFile} */
module.exports = {
    schemaFile: './openapi.json',
    apiFile: './src/services/baseApi.ts',
    apiImport: 'baseApi',
    outputFile: './src/services/generatedApi.ts',
    exportName: 'generatedApi',
    hooks: true,
    filterEndpoints: (name) => !FILES.has(name),
};
