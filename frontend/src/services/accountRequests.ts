import { sendRequest, type Request } from './baseQuery';
import type { Message } from './generatedApi';

// The three requests that carry a new password, the setup code or an account link's token. They
// aren't in the API slice: RTK Query keeps a mutation's arguments in the store
// (state.api.mutations[id].originalArgs) and puts them on its actions (meta.arg.originalArgs),
// where the Redux DevTools and any middleware see them (#432). So they're left out of the
// generated endpoints (openapi-config.cjs) and sent from here, the way the slice sends its own
// requests, through baseQuery's sendRequest.
//
// Each answers with the server's message. When the server refuses or can't be reached, it throws
// what a mutation's unwrap() throws, the status and the body the server answered with, so
// readableError reads it the same way. Nothing here is cached and nothing needs refetching
// afterwards.

const send = async (request: Request, endpoint: string): Promise<Message> => {
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
    return result.data as Message;
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
