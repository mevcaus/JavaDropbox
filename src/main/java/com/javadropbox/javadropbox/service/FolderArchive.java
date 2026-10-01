package com.javadropbox.javadropbox.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes a folder as a zip straight to a stream, so its size never has to fit in memory. */
public final class FolderArchive {

  private FolderArchive() {}

  /**
   * @param rootName the name of the top-level folder inside the zip
   */
  public static void write(Path folder, String rootName, OutputStream out) throws IOException {
    ZipOutputStream zip = new ZipOutputStream(out);
    addFolder(folder, rootName, zip);
    // finish() rather than close(): the caller owns the underlying stream.
    zip.finish();
  }

  private static void addFolder(Path folder, String prefix, ZipOutputStream zip)
      throws IOException {
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(StoragePaths.recheck(folder))) {
      for (Path entry : entries) {
        // A symlink could lead outside the serving directory or back into this folder. Read once
        // without following links, so what is checked is what gets opened.
        BasicFileAttributes attributes =
            Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink()) {
          continue;
        }
        String name = prefix + "/" + entry.getFileName();
        if (attributes.isDirectory()) {
          addFolder(entry, name, zip);
        } else {
          zip.putNextEntry(new ZipEntry(name));
          try (InputStream in = Files.newInputStream(entry, LinkOption.NOFOLLOW_LINKS)) {
            in.transferTo(zip);
          }
          zip.closeEntry();
        }
      }
    }
  }
}
