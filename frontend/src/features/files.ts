// The file endpoints the app links to rather than loading into the store, and helpers for the file
// tree, which comes from the API slice (apiSlice.ts).
import type { FileTreeNode, PickedFile, UploadLimits } from '../services/apiSlice';

// A file's contents, served as a download or for the browser to show (see FileController).
export const DOWNLOAD_ENDPOINT = '/api/files/download';
export const PREVIEW_ENDPOINT = '/api/files/preview';

/** Where a file from the folder picker sits inside the chosen folder, e.g. "Photos/2024/beach.jpg". */
export const relativePathOf = (file: File): string => file.webkitRelativePath || file.name;

/**
 * The files of a folder grouped by the folder each one goes into below path, parents first.
 * files is a list of { file, relativePath }, as uploadFolder takes it.
 */
export const filesByFolder = (files: readonly PickedFile[], path: string): [string, File[]][] => {
    const groups = new Map<string, File[]>();
    for (const { file, relativePath } of files) {
        const folder = relativePath.split('/').slice(0, -1).join('/');
        const target = [path, folder].filter(Boolean).join('/');
        const group = groups.get(target);
        if (group) group.push(file);
        else groups.set(target, [file]);
    }
    return [...groups].sort(([a], [b]) => a.localeCompare(b));
};

// What the multipart framing adds to a request, generously: per file its boundary and headers,
// with a name of up to 255 characters, and per request the closing boundary and the path's part.
const PART_OVERHEAD = 1024;
const REQUEST_OVERHEAD = 1024;

/**
 * The largest file the server takes, alone in a request with its framing, or Infinity for no
 * limit.
 */
export const largestUpload = ({ maxFileBytes, maxRequestBytes }: UploadLimits): number =>
    Math.min(maxFileBytes ?? Infinity, maxRequestBytes == null ? Infinity : maxRequestBytes - REQUEST_OVERHEAD - PART_OVERHEAD);

/**
 * The files bound for one folder split into requests the server takes: none with more files or
 * more bytes than limits allow, as GET /api/files/upload-limits answers them. A file too large
 * for a request of its own still gets one, for the server to refuse with its own message.
 */
export const uploadBatches = (files: readonly File[], path: string, limits: UploadLimits): File[][] => {
    const { maxFilesPerRequest, maxRequestBytes } = limits;
    const empty = REQUEST_OVERHEAD + new TextEncoder().encode(path).length;
    const batches: File[][] = [];
    let batch: File[] = [];
    let bytes = empty;
    for (const file of files) {
        const size = file.size + PART_OVERHEAD;
        const full =
            batch.length > 0 &&
            ((maxFilesPerRequest != null && batch.length >= maxFilesPerRequest) ||
                (maxRequestBytes != null && bytes + size > maxRequestBytes));
        if (full) {
            batches.push(batch);
            batch = [];
            bytes = empty;
        }
        batch.push(file);
        bytes += size;
    }
    if (batch.length > 0) batches.push(batch);
    return batches;
};

// One shared empty result, so a component comparing by reference doesn't see a change every time.
const NO_FILES: readonly FileTreeNode[] = Object.freeze([]);

/**
 * The items in a folder of the file tree: `tree` is the whole tree, as getFileTree answers it, and
 * `path` the folder, like "docs/2026", empty for the root. None when the tree or the folder isn't
 * there.
 */
export const filesIn = (tree: readonly FileTreeNode[] | undefined, path: string): readonly FileTreeNode[] => {
    if (!Array.isArray(tree)) {
        return NO_FILES;
    }
    let level = tree;
    for (const part of path ? path.split('/') : []) {
        const folder = level.find((node) => node.name === part && node.isDirectory);
        if (!folder || !Array.isArray(folder.children)) {
            return NO_FILES;
        }
        level = folder.children;
    }
    return level;
};

/** The total size of the items at the top of the tree, in bytes. */
export const totalSize = (tree: readonly FileTreeNode[] | undefined): number =>
    Array.isArray(tree) ? tree.reduce((sum, node) => sum + node.size, 0) : 0;
