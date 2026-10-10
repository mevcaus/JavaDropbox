package com.javadropbox.javadropbox.dto;

import com.javadropbox.javadropbox.model.PreviewType;
import java.time.Instant;

/**
 * A file or folder a search found, as it is stored now.
 *
 * @param size in bytes; {@code null} for a folder
 * @param lastModified {@code null} where the store does not know, as for some folders in S3
 * @param previewType as in the file tree; {@code null} for folders and files that can only be
 *     downloaded
 * @param snippet where its text matches; {@code null} when only its name does
 */
public record SearchResult(
    String name,
    String relativePath,
    boolean isDirectory,
    Long size,
    Instant lastModified,
    PreviewType previewType,
    Snippet snippet) {}
