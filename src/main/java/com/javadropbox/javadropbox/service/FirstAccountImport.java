package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.OneTimeStepRepository;
import com.javadropbox.javadropbox.repository.UserRepository;
import com.javadropbox.javadropbox.service.FileStore.Entry;
import com.javadropbox.javadropbox.service.StoragePaths.Home;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Gives the first account the files of an install from before accounts had folders of their own,
 * once. Those files are at the top of the store, where the one shared space was; their rows were
 * given to the first account by the V7 migration, with paths that hold relative to its folder.
 *
 * <p>It moves what is at the top of the store into the first account's folder, then imports that
 * folder, so that files copied in by hand get rows too. It runs at the first start with an account
 * and no record of having run, which is the first start after upgrading, and when setup creates the
 * first account, so that the welcome file and anything put in the store before setup are that
 * account's. Once everything at the top has been moved and the folder imported, it is recorded as
 * done (a move or an import that failed is tried again at the next start), and from then on nothing
 * at the top of the store is moved: a start only names what is there in the log.
 */
@Component
public class FirstAccountImport {

  /** The name it is recorded as done under. */
  static final String STEP = "first-account-import";

  /** Recorded by setup when the new account's folder held another database's files. */
  static final String FOREIGN_FOLDER = "first-account-folder-foreign";

  /** A filesystem's own folder at the top of a mount point, never anyone's file. */
  private static final String LOST_AND_FOUND = "lost+found";

  /** How many items left at the top a start names in the log. */
  private static final int NAMES_LOGGED = 20;

  private static final Logger log = LoggerFactory.getLogger(FirstAccountImport.class);

  private final StoragePaths storagePaths;
  private final FileStore store;
  private final UserRepository users;
  private final OneTimeStepRepository steps;
  private final ImportService imports;
  private final SearchIndex searchIndex;

  public FirstAccountImport(
      StoragePaths storagePaths,
      FileStore store,
      UserRepository users,
      OneTimeStepRepository steps,
      ImportService imports,
      SearchIndex searchIndex) {
    this.storagePaths = storagePaths;
    this.store = store;
    this.users = users;
    this.steps = steps;
    this.imports = imports;
    this.searchIndex = searchIndex;
  }

  /**
   * Runs it if it hasn't run yet, and otherwise names what is at the top of the store. At every
   * start, before the web server starts, so nobody sees the first account's folder half-filled.
   */
  @PostConstruct
  public void atStartup() {
    users
        .findFirstByOrderByIdAsc()
        .ifPresent(
            first -> {
              if (steps.existsById(STEP)) {
                nameLooseItems(first);
              } else {
                run(first, "the upgrade");
              }
            });
  }

  /**
   * Gives {@code owner}, the account setup has just created, what is in the store already. A folder
   * of its own that already holds files has another database's, from a store used before: those are
   * left without rows, for an admin to import if they are the account's. That is decided here,
   * before anything is moved in, and kept, so that a setup cut short and finished by the next start
   * decides the same way.
   */
  public void forNewFirstAccount(User owner) {
    boolean foreign;
    try {
      foreign = !store.list(storagePaths.home(owner).key()).isEmpty();
    } catch (IOException e) {
      log.error(
          "Could not look in the folder of \"{}\", the new first account", owner.getUsername(), e);
      foreign = true;
    }
    if (foreign) {
      log.warn(
          "The folder of \"{}\", the new first account, already holds files, which look like those"
              + " of an account another database knew. They are left without rows; if they are"
              + " this account's, import them from the Users page.",
          owner.getUsername());
      steps.recordDone(FOREIGN_FOLDER, Instant.now());
    }
    run(owner, "setup");
  }

  // Recorded as done only once everything worked: the moves, and the import where there is one.
  // An account that setup never checked was created by a release from before this one, with the
  // database and the store used together since, so its folder is imported.
  private void run(User owner, String occasion) {
    if (!moveLooseItems(owner)) {
      log.warn("The first account's files will be moved at the next start");
      return;
    }
    boolean importFolder = !steps.existsById(FOREIGN_FOLDER);
    if (importFolder) {
      try {
        imports.importFiles(owner.getId(), occasion);
      } catch (IOException | RuntimeException e) {
        log.error(
            "Could not import the files of \"{}\" after {}; the next start tries again. If the"
                + " store is the wrong one, point the app at the right one before restarting.",
            owner.getUsername(),
            occasion,
            e);
        return;
      }
    }
    steps.recordDone(STEP, Instant.now());
  }

  // Hidden names are the app's own folders, or files the file tree never showed.
  private List<Entry> looseItems() throws IOException {
    return store.list("").stream()
        .filter(entry -> !entry.name().startsWith("."))
        .filter(entry -> !entry.name().equals(LOST_AND_FOUND))
        .toList();
  }

  // Whether everything at the top could be dealt with: moved, or left because the folder has an
  // item by that name. A listing or a move that failed is worth another try.
  private boolean moveLooseItems(User owner) {
    List<Entry> loose;
    try {
      loose = looseItems();
    } catch (IOException e) {
      log.error("Could not look for files to move into the first account's folder", e);
      return false;
    }
    if (loose.isEmpty()) {
      return true;
    }

    Home home = storagePaths.home(owner);
    int moved = 0;
    boolean failed = false;
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
        failed = true;
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
    return !failed;
  }

  // Nothing at the top of the store is anyone's once the upgrade is done. Saying so beats a file
  // copied there silently never showing up.
  private void nameLooseItems(User first) {
    try {
      // Sorted, since a store lists in no particular order: the same names each start.
      List<String> names = looseItems().stream().map(Entry::name).sorted().toList();
      if (!names.isEmpty()) {
        String named = String.join(", ", names.subList(0, Math.min(names.size(), NAMES_LOGGED)));
        if (names.size() > NAMES_LOGGED) {
          named += " and " + (names.size() - NAMES_LOGGED) + " more";
        }
        log.warn(
            "{} at the top of {} belong to no account and are left there: {}. Files copied in by"
                + " hand go in an account's folder, {}/<account id>/ (\"{}\" is {}), and are"
                + " imported from the Users page.",
            names.size() == 1 ? "1 item is" : names.size() + " items are",
            store.description(),
            named,
            StoragePaths.HOMES_DIR,
            first.getUsername(),
            first.getId());
      }
    } catch (IOException e) {
      log.warn("Could not look at the top of {}: {}", store.description(), e.toString());
    }
  }
}
