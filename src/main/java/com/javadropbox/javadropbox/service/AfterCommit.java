package com.javadropbox.javadropbox.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Defers clean-up in the {@link FileStore} until the database change that makes it safe has
 * committed. Deleting a version's file before its row is gone would leave a row pointing at nothing
 * if the transaction then rolled back; the other way round only ever leaves an orphaned file.
 */
final class AfterCommit {

  private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

  private AfterCommit() {}

  interface Cleanup {
    void run() throws Exception;
  }

  static void run(String description, Cleanup cleanup) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      runLogged(description, cleanup);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            runLogged(description, cleanup);
          }
        });
  }

  private static void runLogged(String description, Cleanup cleanup) {
    try {
      cleanup.run();
    } catch (Exception e) {
      log.warn("Clean-up failed: {}", description, e);
    }
  }
}
