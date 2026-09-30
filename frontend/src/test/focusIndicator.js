// A class that draws something visible on focus: a ring with a width, an outline, an underline, or
// a border, background or shadow change. A ring colour alone draws nothing.
const REPLACEMENT = /(^|\s)(focus|focus-visible):(ring(-[0-8])?|outline(?!-none)\S*|underline|border-\S+|bg-\S+|shadow\S*)(\s|$)/;

/**
 * The buttons and links in a container that remove the browser's focus outline without drawing
 * anything in its place, named so a failure says which control it is.
 */
export const controlsWithoutFocusIndicator = (container) =>
    [...container.querySelectorAll('button, a[href]')]
        .filter((el) => el.className.includes('focus:outline-none') && !REPLACEMENT.test(el.className))
        .map((el) => el.getAttribute('aria-label') || el.textContent.trim());
