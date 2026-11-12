package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.MigratedDatabase;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("Database locks")
@Timeout(60)
class DatabaseLocksTests {

  private static final DataSource DATABASE = MigratedDatabase.create();
  private static final long KEY = 42;

  // Each call on a connection of its own, closed after: as another server would.
  private final JdbcTemplate jdbc = new JdbcTemplate(DATABASE);

  @Test
  @DisplayName("the lock is held while the work runs, and let go after")
  void heldWhileWorkRuns() throws Exception {
    AtomicBoolean takenElsewhere = new AtomicBoolean(true);

    boolean ran =
        DatabaseLocks.whileHeld(
            jdbc, KEY, Duration.ofSeconds(1), () -> takenElsewhere.set(takeElsewhere()));

    assertThat(ran).isTrue();
    assertThat(takenElsewhere).isFalse();
    assertThat(takeElsewhere()).isTrue();
  }

  // A lock left on a pooled connection would keep every other server waiting.
  @Test
  @DisplayName("work that fails lets go of the lock, and its exception comes out as it was")
  void failedWorkLetsGo() {
    assertThatThrownBy(
            () ->
                DatabaseLocks.whileHeld(
                    jdbc,
                    KEY,
                    Duration.ofSeconds(1),
                    () -> {
                      throw new IOException("the store went away");
                    }))
        .isExactlyInstanceOf(IOException.class)
        .hasMessage("the store went away");

    assertThat(takeElsewhere()).isTrue();
  }

  @Test
  @DisplayName("while another holds the lock, it gives up after the wait, without running the work")
  void givesUpAfterTheWait() throws Exception {
    try (Connection other = DATABASE.getConnection();
        Statement statement = other.createStatement()) {
      statement.execute("SELECT pg_advisory_lock(" + KEY + ")");
      AtomicBoolean ran = new AtomicBoolean();
      long started = System.nanoTime();

      boolean took =
          DatabaseLocks.whileHeld(jdbc, KEY, Duration.ofMillis(500), () -> ran.set(true));

      assertThat(took).isFalse();
      assertThat(ran).isFalse();
      assertThat(Duration.ofNanos(System.nanoTime() - started))
          .isGreaterThanOrEqualTo(Duration.ofMillis(500));
    }
  }

  @Test
  @DisplayName("while another holds the lock, it waits, and runs the work once it is let go")
  void waitsForTheLock() throws Exception {
    Connection other = DATABASE.getConnection();
    try (Statement statement = other.createStatement()) {
      statement.execute("SELECT pg_advisory_lock(" + KEY + ")");
    }
    Thread letGo =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    Thread.sleep(300);
                    other.close();
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                });
    AtomicBoolean ran = new AtomicBoolean();

    boolean took = DatabaseLocks.whileHeld(jdbc, KEY, Duration.ofSeconds(5), () -> ran.set(true));

    letGo.join();
    assertThat(took).isTrue();
    assertThat(ran).isTrue();
  }

  @Test
  @DisplayName("a lock held while work runs is refused inside a transaction")
  void whileHeldNeedsNoTransaction() {
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(DATABASE));

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      try {
                        DatabaseLocks.whileHeld(jdbc, KEY, Duration.ofSeconds(1), () -> {});
                      } catch (IOException e) {
                        throw new IllegalStateException(e);
                      }
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("needs no transaction");
  }

  @Test
  @DisplayName(
      "a lock held until commit is refused outside a transaction, where it would do nothing")
  void holdUntilCommitNeedsATransaction() {
    assertThatThrownBy(() -> DatabaseLocks.holdUntilCommit(jdbc, KEY))
        .isInstanceOf(IllegalStateException.class);
  }

  // Whether another session can take the lock now; let go again at once if it could.
  private boolean takeElsewhere() {
    return Boolean.TRUE.equals(
        jdbc.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, KEY));
  }
}
