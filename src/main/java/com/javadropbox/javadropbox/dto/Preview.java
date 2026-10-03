package com.javadropbox.javadropbox.dto;

import com.javadropbox.javadropbox.model.PreviewType;
import java.nio.file.Path;

/** A file to show in the browser rather than save, and the type it is served as. */
public record Preview(Path path, String filename, PreviewType type, String contentType) {}
