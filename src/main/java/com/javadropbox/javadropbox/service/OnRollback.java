package com.javadropbox.javadropbox.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Undoes a change in the {@link FileStore} if the database transaction it belongs to does not
 * commit. A move in the store is not rolled back with the rows that describe it; without an undo, a
 * failure after the move leaves the store showing a change the database never recorded.
 */
final class OnRollback {

  private static final Logger log = LoggerFactory.getLogger(OnRollback.class);

  private OnRollback() {}

  static void undo(String description, AfterCommit.Cleanup undo) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("No transaction to undo " + description + " with");
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          // An unknown outcome is a failed commit whose rollback was not confirmed. The database
          // rolls a transaction back when its commit fails, so treat it as rolled back.
          @Override
          public void afterCompletion(int status) {
            if (status == STATUS_COMMITTED) {
              return;
            }
            try {
              undo.run();
            } catch (Exception e) {
              log.error("Could not undo {} after a rollback", description, e);
            }
          }
        });
  }
}
