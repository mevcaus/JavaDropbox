package com.javadropbox.javadropbox.dto;

import com.javadropbox.javadropbox.model.PreviewType;
import org.springframework.core.io.Resource;

/** A file to show in the browser rather than save, and the type it is served as. */
public record Preview(Resource content, String filename, PreviewType type, String contentType) {}
