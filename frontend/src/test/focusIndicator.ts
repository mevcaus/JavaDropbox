// A class that draws something visible on focus: a ring with a width, an outline, an underline, or
// a border, background or shadow change. A ring colour alone draws nothing.
const REPLACEMENT = /(^|\s)(focus|focus-visible):(ring(-[0-8])?|outline(?!-none|-hidden)\S*|underline|border-\S+|bg-\S+|shadow\S*)(\s|$)/;

// Tailwind 4 calls it outline-hidden; outline-none still exists and also removes the outline.
const REMOVES_OUTLINE = /(^|\s)focus:outline-(hidden|none)(\s|$)/;

/**
 * The buttons and links in a container that remove the browser's focus outline without drawing
 * anything in its place, named so a failure says which control it is.
 */
export const controlsWithoutFocusIndicator = (container: ParentNode): string[] =>
    [...container.querySelectorAll<HTMLElement>('button, a[href]')]
        .filter((el) => REMOVES_OUTLINE.test(el.className) && !REPLACEMENT.test(el.className))
        .map((el) => el.getAttribute('aria-label') || (el.textContent ?? '').trim());
