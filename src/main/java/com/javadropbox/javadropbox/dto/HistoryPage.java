package com.javadropbox.javadropbox.dto;

import java.util.List;

/** One page of the history, with what a client needs to page through the rest. */
public record HistoryPage(
    List<FileHistoryDto> items, int page, int size, long totalItems, int totalPages) {}
