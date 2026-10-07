package com.javadropbox.javadropbox.dto;

import java.util.List;

/**
 * The passage of a file's text that best matches a search, with the matching words marked.
 *
 * @param text the passage, with "…" where it starts or ends inside the text
 * @param highlights the matching words, as ranges of {@code text} in order: {@code start} is the
 *     first character's index and {@code end} the index after the last, counted in UTF-16 code
 *     units as Java and JavaScript strings are
 */
public record Snippet(String text, List<Highlight> highlights) {

  public record Highlight(int start, int end) {}
}
