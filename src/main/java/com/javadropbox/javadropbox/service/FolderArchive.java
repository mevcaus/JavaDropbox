package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.exception.BadRequestException;
import com.javadropbox.javadropbox.service.FileStore.Entry;
import com.javadropbox.javadropbox.service.ListedItems.Item;
import com.javadropbox.javadropbox.service.StoragePaths.StoragePath;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a folder as a zip straight to a stream, so its size never has to fit in memory. What goes
 * in is what the file list shows below the folder ({@link ListedItems}): no hidden names, so not
 * the app's own folders, upload scratch files, or things like .git or .env that the owner never
 * sees and so never meant to share. Each file's content is found through {@link FileContents}.
 */
public final class FolderArchive {

  private static final Logger log = LoggerFactory.getLogger(FolderArchive.class);

  private FolderArchive() {}

  /**
   * @param folder the folder being zipped
   * @param items what the file list shows below it, in path order
   * @param rootName the name of the top-level folder inside the zip
   */
  static void write(
      FileContents contents,
      StoragePath folder,
      List<Item> items,
      String rootName,
      OutputStream out)
      throws IOException {
    ZipOutputStream zip = new ZipOutputStream(out);
    int below = folder.isRoot() ? 0 : folder.key().length() + 1;
    addFolder(rootName, zip);
    for (Item item : items) {
      String name = rootName + "/" + item.path().substring(below);
      if (item.folder()) {
        addFolder(name, zip);
      } else {
        addFile(contents, folder, item, name, zip);
      }
    }
    // finish() rather than close(): the caller owns the underlying stream.
    zip.finish();
  }

  // An entry of its own, so a folder with nothing to archive still appears in the zip.
  private static void addFolder(String name, ZipOutputStream zip) throws IOException {
    zip.putNextEntry(new ZipEntry(name + "/"));
    zip.closeEntry();
  }

  private static void addFile(
      FileContents contents, StoragePath folder, Item item, String name, ZipOutputStream zip)
      throws IOException {
    // Found and opened before the entry is started, so a file that has just gone leaves nothing
    // behind. The response is already under way, so failing now would only truncate the zip. The
    // store finds no symlinks, nor special files such as FIFOs, which could block or never end,
    // and refuses a way there that passes through a symlink, swapped in on disk since: that file
    // is left out too.
    Optional<Entry> content;
    try {
      content =
          contents.of(folder.home().resolveItem(item.path())).filter(entry -> !entry.isDirectory());
    } catch (BadRequestException e) {
      log.warn("Left a file out of a zip: {}", e.getMessage());
      return;
    }
    if (content.isEmpty()) {
      return;
    }
    InputStream in;
    try {
      in = contents.open(content.get());
    } catch (NoSuchFileException e) {
      return;
    }
    try (in) {
      zip.putNextEntry(new ZipEntry(name));
      in.transferTo(zip);
      zip.closeEntry();
    }
  }
}
