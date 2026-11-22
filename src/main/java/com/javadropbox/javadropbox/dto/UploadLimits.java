package com.javadropbox.javadropbox.dto;

import org.jspecify.annotations.Nullable;

/**
 * What one upload request ({@code POST /api/files}) may carry, so a client can send a large upload
 * in batches that fit. Each is null when there's no limit.
 *
 * @param maxFileBytes the largest file, in bytes
 * @param maxRequestBytes the largest request, in bytes, multipart framing included
 * @param maxFilesPerRequest the most files, one part fewer than the server takes, since the path is
 *     a part too
 */
public record UploadLimits(
    @Nullable Long maxFileBytes,
    @Nullable Long maxRequestBytes,
    @Nullable Integer maxFilesPerRequest) {}
