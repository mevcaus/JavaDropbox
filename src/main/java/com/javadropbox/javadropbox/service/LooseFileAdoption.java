package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.FileStore.Entry;
import com.javadropbox.javadropbox.service.StoragePaths.Home;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Gives the first account whatever is at the top of the store. Before accounts had folders of their
 * own, that is where the one shared space was, so that is where an upgraded install's files are;
 * their rows were given to the first account by the V7 migration, with paths that hold relative to
 * its folder once the files are in it.
 *
 * <p>Runs as the app starts, before it takes requests, and when setup creates the first account, so
 * that files put in the store before setup are that account's too. Names starting with a dot stay
 * where they are: they are the app's own folders, or hidden files the file tree never showed. On
 * the local disk each item is moved with a single rename, so an interrupted start leaves every item
 * either moved or still waiting for the next one.
 */
@Component
public class LooseFileAdoption {

  private static final Logger log = LoggerFactory.getLogger(LooseFileAdoption.class);

  private final StoragePaths storagePaths;
  private final FileStore store;
  private final UserRepository users;
  private final SearchIndex searchIndex;

  public LooseFileAdoption(
      StoragePaths storagePaths, FileStore store, UserRepository users, SearchIndex searchIndex) {
    this.storagePaths = storagePaths;
    this.store = store;
    this.users = users;
    this.searchIndex = searchIndex;
  }

  // Before the web server starts, so nobody sees the first account's folder half-filled.
  @PostConstruct
  void adoptAtStartup() {
    users.findFirstByOrderByIdAsc().ifPresent(this::adopt);
  }

  /** Moves everything at the top of the store into {@code owner}'s folder. */
  public void adopt(User owner) {
    List<Entry> loose;
    try {
      loose = store.list("").stream().filter(entry -> !entry.name().startsWith(".")).toList();
    } catch (IOException e) {
      log.error("Could not look for files to move into the first account's folder", e);
      return;
    }
    if (loose.isEmpty()) {
      return;
    }

    Home home = storagePaths.home(owner);
    int moved = 0;
    for (Entry entry : loose) {
      String target = home.key() + "/" + entry.name();
      try {
        if (store.exists(target)) {
          log.warn(
              "Left {} where it is: {} already has an item by that name", entry.key(), home.key());
          continue;
        }
        store.move(entry.key(), target);
        moved++;
      } catch (IOException e) {
        log.error("Could not move {} into {}", entry.key(), home.key(), e);
      }
    }
    if (moved > 0) {
      log.info(
          "Moved {} items from the top of {} into the folder of \"{}\", the first account",
          moved,
          store.description(),
          owner.getUsername());
      searchIndex.changed(home, "");
    }
  }
}
