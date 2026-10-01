// The backend refuses names that start with a dot (its file tree hides them, so such an item would
// be stored but never shown). Checking here too explains the rule before anything is sent.
export const DOT_NAME_RULE = 'Names cannot start with a dot.';

export const startsWithDot = (name) => name.startsWith('.');
