import type { Dispatch, UnknownAction } from '@reduxjs/toolkit';
import { sendRequest, type Request } from './baseQuery';
import { apiSlice } from './apiSlice';
import type { components } from './apiSchema';
import type { Message } from './generatedApi';

// The requests that carry a secret, there or back, and so aren't in the API slice. RTK Query puts
// a mutation's arguments on every action it dispatches for it (meta.arg.originalArgs), and keeps
// its answer in the store (state.api.mutations[id].data) and on its fulfilled action, where the
// Redux DevTools and any middleware see them. Three send a new password, the setup code or an
// account link's token (#432). Three answer with a link that works for whoever holds it: an
// invitation or a password reset link sets an account's password, and a share link opens the item
// (#436). They're left out of the generated endpoints (openapi-config.cjs) and sent from here, the
// way the slice sends its own requests, through baseQuery's sendRequest.
//
// When the server refuses or can't be reached, each throws what a mutation's unwrap() throws, the
// status and the body the server answered with, so readableError reads it the same way. Nothing
// here is cached.

const send = async <T>(request: Request, endpoint: string): Promise<T> => {
    let result: Awaited<ReturnType<typeof sendRequest>>;
    try {
        result = await sendRequest(request, endpoint);
    } catch (bug) {
        // A mistake in the app rather than a failed request, logged as RTK logs one in the slice.
        // It never carries the request's body: sendRequest turns every axios error into a result.
        console.error(`${endpoint} failed`, bug);
        throw bug;
    }
    if ('error' in result) throw result.error;
    return result.data as T;
};

/** What the setup page sends to create the admin account. */
export type SetupDetails = { code: string; username: string; password: string };

/** Creates the admin account on a fresh install, with the setup code from the server's log. */
export const completeSetup = (details: SetupDetails): Promise<Message> =>
    send({ url: '/setup', method: 'POST', body: details }, 'completeSetup');

// The token comes from the address bar, so it's encoded: one holding "../" would otherwise send
// the request somewhere else in the API.

/** Creates the invited account with this password, which uses up the invitation. */
export const acceptInvite = (token: string, password: string): Promise<Message> =>
    send({ url: `/api/invite/${encodeURIComponent(token)}`, method: 'POST', body: { password } }, 'acceptInvite');

/** Sets the account's new password from a reset link, which uses up the link. */
export const resetPassword = (token: string, password: string): Promise<Message> =>
    send(
        { url: `/api/reset-password/${encodeURIComponent(token)}`, method: 'POST', body: { password } },
        'resetPassword',
    );

/** A link just made, to show once: it can't be shown again. */
export type NewLink = components['schemas']['NewLink'];

type Tags = Parameters<typeof apiSlice.util.invalidateTags>[0];

// Asks again for the lists a new link changes, failed or not, as the slice's cache tags did for
// these requests: a failure can still have changed what's listed.
const sendLink = async (dispatch: Dispatch<UnknownAction>, request: Request, endpoint: string, tags: Tags) => {
    try {
        return await send<NewLink>(request, endpoint);
    } finally {
        if (tags.length > 0) dispatch(apiSlice.util.invalidateTags(tags));
    }
};

/** Who an invitation is for, and the role and quota their account starts with. */
export type InviteDetails = { username: string; role?: string; quota?: string };

/** Makes a one-time link at which the invitee chooses a password. Admins only. */
export const createInvite = (dispatch: Dispatch<UnknownAction>, details: InviteDetails): Promise<NewLink> =>
    sendLink(dispatch, { url: '/api/admin/invites', method: 'POST', body: details }, 'createInvite', ['Invites']);

/** Makes a one-time link that sets a new password for the account with this id. Admins only. */
export const createPasswordResetLink = (dispatch: Dispatch<UnknownAction>, id: number): Promise<NewLink> =>
    sendLink(dispatch, { url: `/api/admin/users/${id}/password-reset`, method: 'POST' }, 'createPasswordResetLink', []);

/** Makes a link that opens the item at path for anyone, until it expires or is revoked. */
export const createShareLink = (
    dispatch: Dispatch<UnknownAction>,
    { path, expirationMinutes }: { path: string; expirationMinutes?: number },
): Promise<NewLink> =>
    sendLink(
        dispatch,
        { url: '/api/share', method: 'POST', body: { path, expirationMinutes } },
        'createShareLink',
        [{ type: 'ShareLinks', id: path }],
    );
