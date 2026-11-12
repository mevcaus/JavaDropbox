package com.javadropbox.javadropbox.dto;

import java.util.List;

/**
 * One search's answer, best match first.
 *
 * @param total how many items match, of which {@code results} holds the best
 * @param indexing whether the index has yet to catch up with the files since the server started, so
 *     that some may be missing
 */
public record SearchResults(List<SearchResult> results, long total, boolean indexing) {}
