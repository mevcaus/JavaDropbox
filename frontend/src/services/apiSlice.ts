import { generatedApi } from './generatedApi';
import type { Message } from './generatedApi';
import { filesByFolder } from '../features/files';
import { REQUEST_BODIES } from './requestBodies';

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
        addTagTypes: ['Files', 'Versions', 'ShareLinks', 'Accounts', 'Invites'],
        endpoints: {
            getFileTree: { providesTags: ['Files'] },
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
            listAccounts: { providesTags: ['Accounts'] },
            listInvites: { providesTags: ['Invites'] },
            setAccountRole: { invalidatesTags: ['Accounts'] },
            setAccountQuota: { invalidatesTags: ['Accounts'] },
            // Disabling an account also withdraws the invitations it made.
            setAccountEnabled: { invalidatesTags: ['Accounts', 'Invites'] },
            // An import gives the account's files rows, which count toward its storage, and may be into
            // the admin's own account.
            importFiles: { invalidatesTags: ['Accounts', 'Files'] },
            createInvite: { invalidatesTags: ['Invites'] },
            // Failed or not: a failure is most often an invitation used or expired meanwhile, which
            // shouldn't stay listed.
            revokeInvite: { invalidatesTags: ['Invites'] },
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
        },
    })
    .injectEndpoints({
        endpoints: (build) => ({
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
                    for (const [folder, batch] of filesByFolder(files, path) as [string, File[]][]) {
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
} = apiSlice;
