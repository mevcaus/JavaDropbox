package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.StoragePaths.Home;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Gives the first account whatever is at the top of the serving directory. Before accounts had
 * folders of their own, that is where the one shared space was, so that is where an upgraded
 * install's files are; their rows were given to the first account by the V7 migration, with paths
 * that hold relative to its folder once the files are in it.
 *
 * <p>Runs as the app starts, before it takes requests, and when setup creates the first account, so
 * that files put in the serving directory before setup are that account's too. Names starting with
 * a dot stay where they are: they are the app's own folders, or hidden files the file tree never
 * showed. Each item is moved with a single rename, so an interrupted start leaves every item either
 * moved or still waiting for the next one.
 */
@Component
public class LooseFileAdoption {

  private static final Logger log = LoggerFactory.getLogger(LooseFileAdoption.class);

  private final StoragePaths storagePaths;
  private final UserRepository users;
  private final SearchIndex searchIndex;

  public LooseFileAdoption(
      StoragePaths storagePaths, UserRepository users, SearchIndex searchIndex) {
    this.storagePaths = storagePaths;
    this.users = users;
    this.searchIndex = searchIndex;
  }

  // Before the web server starts, so nobody sees the first account's folder half-filled.
  @PostConstruct
  void adoptAtStartup() {
    users.findFirstByOrderByIdAsc().ifPresent(this::adopt);
  }

  /** Moves everything at the top of the serving directory into {@code owner}'s folder. */
  public void adopt(User owner) {
    Path root = storagePaths.homesDir().getParent();
    List<Path> loose;
    try (Stream<Path> entries = Files.list(root)) {
      loose = entries.filter(entry -> !entry.getFileName().toString().startsWith(".")).toList();
    } catch (IOException e) {
      log.error("Could not look for files to move into the first account's folder", e);
      return;
    }
    if (loose.isEmpty()) {
      return;
    }

    Home home = storagePaths.home(owner);
    int moved = 0;
    for (Path entry : loose) {
      Path target = home.root().resolve(entry.getFileName().toString());
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        log.warn("Left {} where it is: {} already has an item by that name", entry, home.root());
        continue;
      }
      try {
        Files.move(entry, target);
        moved++;
      } catch (IOException e) {
        log.error("Could not move {} into {}", entry, home.root(), e);
      }
    }
    if (moved > 0) {
      log.info(
          "Moved {} items from {} into the folder of \"{}\", the first account",
          moved,
          root,
          owner.getUsername());
      searchIndex.changed(home, "");
    }
  }
}
