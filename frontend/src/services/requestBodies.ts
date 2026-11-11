// Generated from openapi.json by scripts/request-bodies.mjs (npm run api:client). Do not edit.

export type BodyType = 'form' | 'multipart';

/** How each endpoint that takes a body wants it, by operation id. */
export const REQUEST_BODIES: Readonly<Record<string, BodyType>> = {
    acceptInvite: 'form',
    completeSetup: 'form',
    createFolder: 'multipart',
    createInvite: 'form',
    createShareLink: 'form',
    resetPassword: 'form',
    setAccountEnabled: 'form',
    setAccountQuota: 'form',
    setAccountRole: 'form',
    uploadFiles: 'multipart',
};
