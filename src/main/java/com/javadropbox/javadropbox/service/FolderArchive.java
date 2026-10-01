package com.javadropbox.javadropbox.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
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
      // An entry of its own, so a folder with nothing to archive still appears in the zip. Written
      // once the folder is open, so one that has just vanished leaves nothing behind.
      zip.putNextEntry(new ZipEntry(prefix + "/"));
      zip.closeEntry();
      for (Path entry : entries) {
        String filename = entry.getFileName().toString();
        // Hidden as in the file tree: the app's own directories and upload scratch files, and
        // things like .git or .env that the owner never sees and so never meant to share.
        if (filename.startsWith(".")) {
          continue;
        }
        String name = prefix + "/" + filename;
        try {
          BasicFileAttributes attributes =
              Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
          if (attributes.isDirectory()) {
            addFolder(entry, name, zip);
          } else if (attributes.isRegularFile()) {
            addFile(entry, name, zip);
          }
          // Anything else is left out. A symlink could lead outside the serving directory or
          // back into this folder; reading a FIFO or a device could block or never end.
        } catch (NoSuchFileException e) {
          // Removed since the folder was listed, e.g. an upload's scratch file renamed into place.
          // The response is already under way, so failing now would only truncate the zip.
        }
      }
    }
  }

  private static void addFile(Path file, String name, ZipOutputStream zip) throws IOException {
    // Opened before the entry is started, so a file that has just vanished leaves nothing behind.
    try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      zip.putNextEntry(new ZipEntry(name));
      in.transferTo(zip);
      zip.closeEntry();
    }
  }
}
