package com.javadropbox.javadropbox.service;

import java.util.ArrayDeque;
import java.util.Deque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Undoes a change in the {@link FileStore} if the database transaction it belongs to does not
 * commit. A move in the store is not rolled back with the rows that describe it; without an undo, a
 * failure after the move leaves the store showing a change the database never recorded.
 *
 * <p>A transaction's undos run in the reverse of the order they were registered, so each change is
 * undone on the store as it was right after that change: a file moved into a folder the same
 * transaction created is removed before the folder is.
 */
final class OnRollback {

  private static final Logger log = LoggerFactory.getLogger(OnRollback.class);

  private OnRollback() {}

  static void undo(String description, AfterCommit.Cleanup undo) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("No transaction to undo " + description + " with");
    }
    // Kept in the transaction's own synchronizations, so a transaction started inside it and
    // suspending it (such as a failure being recorded) has undos of its own.
    for (TransactionSynchronization registered :
        TransactionSynchronizationManager.getSynchronizations()) {
      if (registered instanceof Undos undos) {
        undos.push(description, undo);
        return;
      }
    }
    Undos undos = new Undos();
    undos.push(description, undo);
    TransactionSynchronizationManager.registerSynchronization(undos);
  }

  private record Undo(String description, AfterCommit.Cleanup action) {}

  private static final class Undos implements TransactionSynchronization {

    private final Deque<Undo> pending = new ArrayDeque<>();

    void push(String description, AfterCommit.Cleanup action) {
      pending.push(new Undo(description, action));
    }

    // An unknown outcome is a failed commit whose rollback was not confirmed. The database rolls a
    // transaction back when its commit fails, so treat it as rolled back.
    @Override
    public void afterCompletion(int status) {
      if (status == STATUS_COMMITTED) {
        return;
      }
      while (!pending.isEmpty()) {
        Undo undo = pending.pop();
        try {
          undo.action().run();
        } catch (Exception e) {
          log.error("Could not undo {} after a rollback", undo.description(), e);
        }
      }
    }
  }
}
