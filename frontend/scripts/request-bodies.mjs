// Writes src/services/requestBodies.ts: how each endpoint that takes a body wants it, by operation
// id, read from the API spec (npm run api:client). The generated endpoints hand the base query a
// plain object, and the server takes forms and uploads, not JSON (see baseQuery.ts).
import { readFileSync, writeFileSync } from 'node:fs';

const spec = JSON.parse(readFileSync(new URL('../openapi.json', import.meta.url), 'utf8'));
const FORM = 'application/x-www-form-urlencoded';
const MULTIPART = 'multipart/form-data';

const bodies = {};
for (const operations of Object.values(spec.paths)) {
    for (const operation of Object.values(operations)) {
        const types = Object.keys(operation?.requestBody?.content ?? {});
        if (types.length === 0) continue;
        // Multipart where the endpoint takes it, since only multipart carries files.
        if (types.includes(MULTIPART)) bodies[operation.operationId] = 'multipart';
        else if (types.includes(FORM)) bodies[operation.operationId] = 'form';
        else throw new Error(`${operation.operationId} takes ${types.join(', ')}, which baseQuery.ts can't send`);
    }
}

const entries = Object.keys(bodies)
    .sort()
    .map((id) => `    ${id}: '${bodies[id]}',`)
    .join('\n');
writeFileSync(
    new URL('../src/services/requestBodies.ts', import.meta.url),
    `// Generated from openapi.json by scripts/request-bodies.mjs (npm run api:client). Do not edit.\n\n` +
        `export type BodyType = 'form' | 'multipart';\n\n` +
        `/** How each endpoint that takes a body wants it, by operation id. */\n` +
        `export const REQUEST_BODIES: Readonly<Record<string, BodyType>> = {\n${entries}\n};\n`,
);
