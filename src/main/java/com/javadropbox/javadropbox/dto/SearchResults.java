package com.javadropbox.javadropbox.dto;

import java.util.List;

/**
 * One search's answer, best match first.
 *
 * @param total how many items match, of which {@code results} holds the best
 * @param indexing whether the index is still being built, so that some files may be missing
 */
public record SearchResults(List<SearchResult> results, long total, boolean indexing) {}
