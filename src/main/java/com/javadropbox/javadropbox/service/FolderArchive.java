package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.service.FileStore.Entry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.NoSuchFileException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes a folder as a zip straight to a stream, so its size never has to fit in memory. */
public final class FolderArchive {

  private FolderArchive() {}

  /**
   * @param folder the folder's key in {@code store}
   * @param rootName the name of the top-level folder inside the zip
   */
  public static void write(FileStore store, String folder, String rootName, OutputStream out)
      throws IOException {
    ZipOutputStream zip = new ZipOutputStream(out);
    int below = folder.isEmpty() ? 0 : folder.length() + 1;
    store.walk(
        folder,
        entry -> {
          // The store leaves out symlinks, which could lead outside the account's folder or back
          // into this one, and special files such as FIFOs, which could block or never end.
          boolean isTop = entry.key().equals(folder);
          // Hidden as in the file tree: the app's own directories and upload scratch files, and
          // things like .git or .env that the owner never sees and so never meant to share.
          if (!isTop && entry.name().startsWith(".")) {
            return FileVisitResult.SKIP_SUBTREE;
          }
          String name = isTop ? rootName : rootName + "/" + entry.key().substring(below);
          if (entry.isDirectory()) {
            // An entry of its own, so a folder with nothing to archive still appears in the zip.
            zip.putNextEntry(new ZipEntry(name + "/"));
            zip.closeEntry();
          } else {
            addFile(store, entry, name, zip);
          }
          return FileVisitResult.CONTINUE;
        });
    // finish() rather than close(): the caller owns the underlying stream.
    zip.finish();
  }

  private static void addFile(FileStore store, Entry file, String name, ZipOutputStream zip)
      throws IOException {
    // Opened before the entry is started, so a file that has just vanished leaves nothing behind.
    InputStream in;
    try {
      in = store.open(file.key());
    } catch (NoSuchFileException e) {
      // Removed since the folder was listed, e.g. an upload's scratch file moved into place. The
      // response is already under way, so failing now would only truncate the zip.
      return;
    }
    try (in) {
      zip.putNextEntry(new ZipEntry(name));
      in.transferTo(zip);
      zip.closeEntry();
    }
  }
}
