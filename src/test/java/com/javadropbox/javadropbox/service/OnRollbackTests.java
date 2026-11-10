package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("Undoing store changes on rollback")
class OnRollbackTests {

  private final List<String> undone = new ArrayList<>();
  private final InMemoryTransactions transactions = new InMemoryTransactions();

  @Test
  @DisplayName("a rollback runs the transaction's undos newest first")
  void undosRunNewestFirst() {
    assertThatThrownBy(
            () ->
                required()
                    .executeWithoutResult(
                        status -> {
                          undo("folder");
                          undo("file");
                          throw new IllegalStateException("simulated failure");
                        }))
        .hasMessage("simulated failure");

    assertThat(undone).containsExactly("file", "folder");
  }

  @Test
  @DisplayName("a commit runs no undos")
  void commitRunsNothing() {
    required().executeWithoutResult(status -> undo("file"));

    assertThat(undone).isEmpty();
  }

  @Test
  @DisplayName("an undo registered by a joining participant goes on the same stack")
  void joinedUndosShareTheStack() {
    assertThatThrownBy(
            () ->
                required()
                    .executeWithoutResult(
                        status -> {
                          undo("outer first");
                          required().executeWithoutResult(inner -> undo("joined"));
                          undo("outer last");
                          throw new IllegalStateException("simulated failure");
                        }))
        .hasMessage("simulated failure");

    assertThat(undone).containsExactly("outer last", "joined", "outer first");
  }

  @Test
  @DisplayName("a separate inner transaction undoes only its own changes")
  void separateTransactionsKeepTheirOwnUndos() {
    TransactionTemplate separate = new TransactionTemplate(transactions);
    separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    required()
        .executeWithoutResult(
            status -> {
              undo("outer");
              assertThatThrownBy(
                      () ->
                          separate.executeWithoutResult(
                              inner -> {
                                undo("inner");
                                throw new IllegalStateException("simulated failure");
                              }))
                  .hasMessage("simulated failure");
              assertThat(undone).containsExactly("inner");
              status.setRollbackOnly();
            });

    assertThat(undone).containsExactly("inner", "outer");
  }

  private TransactionTemplate required() {
    return new TransactionTemplate(transactions);
  }

  private void undo(String name) {
    OnRollback.undo(name, () -> undone.add(name));
  }

  /** Transactions with nothing behind them, only Spring's synchronization and propagation. */
  private static final class InMemoryTransactions extends AbstractPlatformTransactionManager {

    private int depth;

    InMemoryTransactions() {
      setTransactionSynchronization(SYNCHRONIZATION_ALWAYS);
    }

    private record Transaction(boolean existing) {}

    @Override
    protected Object doGetTransaction() {
      return new Transaction(depth > 0);
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
      return ((Transaction) transaction).existing();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      depth++;
    }

    @Override
    protected Object doSuspend(Object transaction) {
      depth--;
      return Boolean.TRUE;
    }

    @Override
    protected void doResume(Object transaction, Object suspendedResources) {
      depth++;
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}

    @Override
    protected void doSetRollbackOnly(DefaultTransactionStatus status) {}

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
      depth--;
    }
  }
}
