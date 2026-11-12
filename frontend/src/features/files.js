// The file endpoints the app links to rather than loading into the store, and helpers for the file
// tree, which comes from the API slice (apiSlice.ts).

// A file's contents, served as a download or for the browser to show (see FileController).
export const DOWNLOAD_ENDPOINT = '/api/files/download';
export const PREVIEW_ENDPOINT = '/api/files/preview';

/** Where a file from the folder picker sits inside the chosen folder, e.g. "Photos/2024/beach.jpg". */
export const relativePathOf = (file) => file.webkitRelativePath || file.name;

/**
 * The files of a folder grouped by the folder each one goes into below path, parents first.
 * files is a list of { file, relativePath }, as uploadFolder takes it.
 */
export const filesByFolder = (files, path) => {
    const groups = new Map();
    for (const { file, relativePath } of files) {
        const folder = relativePath.split('/').slice(0, -1).join('/');
        const target = [path, folder].filter(Boolean).join('/');
        if (!groups.has(target)) groups.set(target, []);
        groups.get(target).push(file);
    }
    return [...groups].sort(([a], [b]) => a.localeCompare(b));
};

// One shared empty result, so a component comparing by reference doesn't see a change every time.
const NO_FILES = Object.freeze([]);

/**
 * The items in a folder of the file tree.
 * @param {Array|undefined} tree - The whole tree, as getFileTree answers it
 * @param {string} path - The folder, as a path like "docs/2026"; empty for the root
 * @returns {Array} The folder's items, or none when the tree or the folder isn't there
 */
export const filesIn = (tree, path) => {
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
export const totalSize = (tree) =>
    Array.isArray(tree) ? tree.reduce((sum, node) => sum + node.size, 0) : 0;
