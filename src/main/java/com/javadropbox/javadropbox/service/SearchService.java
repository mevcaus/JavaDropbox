package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.dto.SearchResult;
import com.javadropbox.javadropbox.dto.SearchResults;
import com.javadropbox.javadropbox.exception.BadRequestException;
import com.javadropbox.javadropbox.exception.NotFoundException;
import com.javadropbox.javadropbox.model.PreviewType;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileMetadataRepository;
import com.javadropbox.javadropbox.repository.ListedRow;
import com.javadropbox.javadropbox.service.StoragePaths.Home;
import com.javadropbox.javadropbox.service.StoragePaths.StoragePath;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
  private final FileMetadataRepository files;
  private final SearchIndex index;
  private final AuthService authService;

  public SearchService(
      StoragePaths storagePaths,
      FileMetadataRepository files,
      SearchIndex index,
      AuthService authService) {
    this.storagePaths = storagePaths;
    this.files = files;
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
    if (!isFolder(folder)) {
      throw new NotFoundException("Folder not found: " + folder.key());
    }

    SearchIndex.Hits hits = index.search(home, folder.key(), text, limit);
    Map<String, ListedRow> rows = rowsOf(home, hits.hits());
    List<SearchResult> results = new ArrayList<>(hits.hits().size());
    for (SearchIndex.Hit hit : hits.hits()) {
      SearchResult result = result(home, hit, rows.get(hit.path()));
      if (result != null) {
        results.add(result);
      }
    }
    return new SearchResults(results, hits.total(), !hits.complete());
  }

  // A folder as the file list shows it: the root, a row that is one, or one that rows are below.
  private boolean isFolder(StoragePath folder) {
    if (folder.isRoot()) {
      return true;
    }
    long owner = folder.home().userId();
    return files
            .findByPath(owner, folder.key())
            .map(row -> Boolean.TRUE.equals(row.getIsDirectory()))
            .orElse(false)
        || files.existsBelow(owner, FileMetadataRepository.below(folder.key()));
  }

  // The rows of the hits, in one query, by path.
  private Map<String, ListedRow> rowsOf(Home home, List<SearchIndex.Hit> hits) {
    if (hits.isEmpty()) {
      return Map.of();
    }
    List<String> paths = hits.stream().map(SearchIndex.Hit::path).distinct().toList();
    return files.listAt(home.userId(), paths).stream()
        .collect(Collectors.toMap(ListedRow::path, Function.identity()));
  }

  // The hit as the file list shows it now, from its row. One that has gone since it was indexed,
  // or that the file list would not show, is left out, and the index told to look at it again.
  private SearchResult result(Home home, SearchIndex.Hit hit, ListedRow row) {
    StoragePath item;
    try {
      item = home.resolveItem(hit.path());
    } catch (BadRequestException e) {
      index.changed(home, hit.path());
      return null;
    }
    boolean listed = ListedItems.isListable(item.key());
    if (listed && row == null) {
      // A folder with no row of its own is still listed while rows are below it.
      listed = files.existsBelow(home.userId(), FileMetadataRepository.below(item.key()));
    }
    if (!listed) {
      index.changed(home, hit.path());
      return null;
    }
    boolean isDirectory = row == null || Boolean.TRUE.equals(row.isDirectory());
    return new SearchResult(
        item.name(),
        item.key(),
        isDirectory,
        isDirectory ? null : (row.size() == null ? 0L : row.size()),
        row == null ? null : row.updatedAt(),
        isDirectory ? null : PreviewType.of(item.name()).orElse(null),
        hit.snippet());
  }
}
