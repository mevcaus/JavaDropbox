import { generatedApi } from './generatedApi';
import type { DemoInfo, Message } from './generatedApi';
import { filesByFolder } from '../features/files';
import { REQUEST_BODIES } from './requestBodies';

// The generated types the rest of the app reads, re-exported so they're imported from here.
export type {
    AccountDto,
    CurrentUser,
    FileTreeNode,
    NewLink,
    SearchResult,
    SearchResults,
    ShareLinkDto,
} from './generatedApi';

/** A file with its place inside a folder being uploaded, e.g. "Trip/Day 1/a.jpg". */
export type PickedFile = { file: File; relativePath: string };

/**
 * The API slice with its cache tags, plus the requests the spec can't describe. Import the hooks
 * from here rather than from generatedApi, so the tags apply.
 *
 * Every change to the files invalidates the one Files tag, which refetches the tree, whether the
 * change worked or not: a failed upload may have stored the files before the one that failed, and a
 * failed delete may have removed part of a folder, or found the item already gone.
 */
export const apiSlice = generatedApi
    .enhanceEndpoints({
        // Files: one tag for the whole tree, which comes back as one answer. Once it's listed per
        // folder (#228), each folder can have its own. Versions: a file's, by its id. ShareLinks:
        // an item's active links, by its path. Accounts and Invites: the admin page's two lists.
        // Storage: what the signed-in account stores, and its quota.
        addTagTypes: ['Files', 'Versions', 'ShareLinks', 'Accounts', 'Invites', 'Storage'],
        endpoints: {
            // Each listing also asks for storage use again, so the meter follows the files whatever
            // changed them: a change here, in another tab, or the demo's daily reset.
            getFileTree: {
                providesTags: ['Files'],
                async onQueryStarted(_arg, { dispatch, queryFulfilled }) {
                    try {
                        await queryFulfilled;
                    } catch {
                        return;
                    }
                    dispatch(apiSlice.util.invalidateTags(['Storage']));
                },
            },
            getStorageUsage: { providesTags: ['Storage'] },
            // A search answers from the files as they are, so a change to them asks again, once any
            // search already on its way has answered.
            searchFiles: { providesTags: ['Files'] },
            // Uploading over a file keeps its old content as a version, and a delete takes the item's
            // versions and links with it, so those lists are asked again too. Only an open dialog's
            // list is subscribed; the rest are just dropped.
            uploadFiles: { invalidatesTags: ['Files', 'Versions'] },
            deleteItem: { invalidatesTags: ['Files', 'Versions', 'ShareLinks'] },
            createFolder: { invalidatesTags: ['Files'] },
            listFileVersions: { providesTags: (_result, _error, { fileId }) => [{ type: 'Versions', id: fileId }] },
            // A restore changes the file, and in place it also keeps the current content as a version.
            restoreVersion: {
                invalidatesTags: (_result, _error, { fileId }) => ['Files', { type: 'Versions', id: fileId }],
            },
            listShareLinks: { providesTags: (_result, _error, { path }) => [{ type: 'ShareLinks', id: path }] },
            createShareLink: {
                invalidatesTags: (_result, _error, { body }) => [{ type: 'ShareLinks', id: body.path }],
            },
            // A link's id doesn't say whose item it shares, so every list of links is asked again. Until
            // that answers, the revoked link is taken out of each list already. (A listing already in
            // flight can still bring it back for a moment; the dialog disables Revoke while it loads.)
            revokeShareLink: {
                invalidatesTags: ['ShareLinks'],
                async onQueryStarted({ id }, { dispatch, getState, queryFulfilled }) {
                    try {
                        await queryFulfilled;
                    } catch {
                        return;
                    }
                    for (const args of generatedApi.util.selectCachedArgsForQuery(getState(), 'listShareLinks')) {
                        dispatch(
                            generatedApi.util.updateQueryData('listShareLinks', args, (links) =>
                                links.filter((link) => link.id !== id),
                            ),
                        );
                    }
                },
            },
            listAccounts: { providesTags: ['Accounts'] },
            listInvites: { providesTags: ['Invites'] },
            setAccountRole: { invalidatesTags: ['Accounts'] },
            // Storage too, for an admin changing their own quota.
            setAccountQuota: { invalidatesTags: ['Accounts', 'Storage'] },
            // Disabling an account also withdraws the invitations it made; enabling one doesn't.
            setAccountEnabled: {
                invalidatesTags: (_result, _error, { body }) => (body.enabled ? ['Accounts'] : ['Accounts', 'Invites']),
            },
            // An import gives the account's files rows, which count toward its storage, and may be into
            // the admin's own account.
            // Storage too: the admin page lists no files, so no listing would ask for it.
            importFiles: { invalidatesTags: ['Accounts', 'Files', 'Storage'] },
            createInvite: { invalidatesTags: ['Invites'] },
            // Failed or not: a failure is most often an invitation used or expired meanwhile, which
            // shouldn't stay listed.
            revokeInvite: { invalidatesTags: ['Invites'] },
        },
    })
    .injectEndpoints({
        endpoints: (build) => ({
            /**
             * The public demo's account and limits, or null on an ordinary install, where /api/demo
             * isn't there. A 404 is an answer, not a failure, so it's asked once rather than again by
             * every screen that shows it. Nothing in it changes while the page is open but the next
             * reset time, which is only shown, so it's kept until the session changes (signing in or
             * out drops the whole cache).
             */
            demoInfo: build.query<DemoInfo | null, void>({
                async queryFn(_arg, _api, _extra, baseQuery) {
                    const result = await baseQuery({ url: '/api/demo' });
                    // Any other failure is reported, so the next screen that shows it asks again.
                    if (result.error && result.error.status !== 404) return { error: result.error };
                    const info = result.data as DemoInfo | undefined;
                    return { data: typeof info?.username === 'string' ? info : null };
                },
                keepUnusedDataFor: 24 * 60 * 60,
            }),
            /**
             * Uploads files into the folder at path, each into the subfolder its relativePath
             * names, so a folder from the folder picker or dropped on the page keeps its structure.
             * The endpoint stores files in one folder per request, so this sends a request per
             * folder, parents first and one after another, and stops at the first that fails. The
             * tree is refreshed once at the end, not after every folder. Empty folders aren't
             * uploaded: neither the picker nor a drop reports them.
             */
            uploadFolder: build.mutation<Message, { files: PickedFile[]; path: string }>({
                async queryFn({ files, path }, _api, _extra, baseQuery) {
                    let last: Message = { message: '' };
                    for (const [folder, batch] of filesByFolder(files, path)) {
                        const result = await baseQuery({
                            url: '/api/files',
                            method: 'POST',
                            body: { files: batch, path: folder },
                            // As the spec says uploadFiles takes it, since this sends the same request.
                            bodyType: REQUEST_BODIES.uploadFiles,
                        });
                        if (result.error) {
                            return { error: result.error };
                        }
                        last = result.data as Message;
                    }
                    return { data: last };
                },
                // As uploadFiles: an upload over a file keeps its old content as a version.
                invalidatesTags: ['Files', 'Versions'],
            }),
        }),
    });

export const {
    useGetFileTreeQuery,
    useUploadFilesMutation,
    useUploadFolderMutation,
    useDeleteItemMutation,
    useCreateFolderMutation,
    useListFileVersionsQuery,
    useRestoreVersionMutation,
    useListShareLinksQuery,
    useCreateShareLinkMutation,
    useRevokeShareLinkMutation,
    useListAccountsQuery,
    useListInvitesQuery,
    useSetAccountEnabledMutation,
    useSetAccountRoleMutation,
    useSetAccountQuotaMutation,
    useCreatePasswordResetLinkMutation,
    useImportFilesMutation,
    useCreateInviteMutation,
    useRevokeInviteMutation,
    useGetStorageUsageQuery,
    useSearchFilesQuery,
    useDemoInfoQuery,
    useDescribeSharedItemQuery,
    useDescribeInviteQuery,
    useDescribePasswordResetQuery,
    useAcceptInviteMutation,
    useResetPasswordMutation,
    useCompleteSetupMutation,
} = apiSlice;
