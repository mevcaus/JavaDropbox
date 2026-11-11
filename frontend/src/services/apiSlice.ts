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
        endpoints: {
            getFileTree: { providesTags: ['Files'] },
            uploadFiles: { invalidatesTags: ['Files'] },
            deleteItem: { invalidatesTags: ['Files'] },
            createFolder: { invalidatesTags: ['Files'] },
            restoreVersion: { invalidatesTags: ['Files'] },
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
                invalidatesTags: ['Files'],
            }),
        }),
    });

export const {
    useGetFileTreeQuery,
    useUploadFilesMutation,
    useUploadFolderMutation,
    useDeleteItemMutation,
    useCreateFolderMutation,
} = apiSlice;
