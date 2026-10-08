package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.dto.SearchResult;
import com.javadropbox.javadropbox.dto.SearchResults;
import com.javadropbox.javadropbox.exception.BadRequestException;
import com.javadropbox.javadropbox.exception.NotFoundException;
import com.javadropbox.javadropbox.model.PreviewType;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.service.StoragePaths.Home;
import com.javadropbox.javadropbox.service.StoragePaths.StoragePath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Answers searches of the signed-in user's folder: checks what was asked, runs it on the {@link
 * SearchIndex}, and checks the hits.
 */
@Service
public class SearchService {

  static final int MAX_QUERY_LENGTH = 200;
  public static final int MAX_RESULTS = 200;

  private final StoragePaths storagePaths;
  private final SearchIndex index;
  private final AuthService authService;

  public SearchService(StoragePaths storagePaths, SearchIndex index, AuthService authService) {
    this.storagePaths = storagePaths;
    this.index = index;
    this.authService = authService;
  }

  /**
   * The best {@code limit} items below the folder at {@code folderPath} (the root when empty) whose
   * name or text has every word of {@code query} in it.
   *
   * @throws BadRequestException for an empty or overlong query, a limit out of range, or a path
   *     that is not allowed
   * @throws NotFoundException if there is no folder at {@code folderPath}
   */
  public SearchResults search(String query, String folderPath, int limit) throws IOException {
    String text = query == null ? "" : query.strip();
    if (text.isEmpty()) {
      throw new BadRequestException("Enter something to search for");
    }
    if (text.length() > MAX_QUERY_LENGTH) {
      throw new BadRequestException(
          "Searches can be at most " + MAX_QUERY_LENGTH + " characters long");
    }
    if (limit < 1 || limit > MAX_RESULTS) {
      throw new BadRequestException("limit must be between 1 and " + MAX_RESULTS);
    }
    User user = authService.requireCurrentUser();
    Home home = storagePaths.home(user);
    StoragePath folder = home.resolve(folderPath);
    if (!Files.isDirectory(folder.path(), LinkOption.NOFOLLOW_LINKS)) {
      throw new NotFoundException("Folder not found: " + folder.key());
    }

    SearchIndex.Hits hits = index.search(home, folder.key(), text, limit);
    List<SearchResult> results = new ArrayList<>(hits.hits().size());
    for (SearchIndex.Hit hit : hits.hits()) {
      SearchResult result = result(home, hit);
      if (result != null) {
        results.add(result);
      }
    }
    return new SearchResults(results, hits.total(), !hits.complete());
  }

  // The hit as it is on disk now. One that has gone since it was indexed, or can now only be
  // reached through a symlink, is left out, and the index told to drop it.
  private SearchResult result(Home home, SearchIndex.Hit hit) {
    StoragePath item;
    BasicFileAttributes attributes;
    try {
      item = home.resolveItem(hit.path());
      attributes =
          Files.readAttributes(item.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (BadRequestException | IOException e) {
      index.changed(home, hit.path());
      return null;
    }
    boolean isDirectory = attributes.isDirectory();
    return new SearchResult(
        item.name(),
        item.key(),
        isDirectory,
        isDirectory ? null : attributes.size(),
        attributes.lastModifiedTime().toInstant(),
        isDirectory ? null : PreviewType.of(item.name()).orElse(null),
        hit.snippet());
  }
}
