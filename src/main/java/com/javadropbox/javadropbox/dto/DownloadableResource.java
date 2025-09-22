package com.javadropbox.javadropbox.dto;

import org.springframework.core.io.Resource;

public record DownloadableResource(
        Resource resource,
        String filename,
        String contentType
) {}