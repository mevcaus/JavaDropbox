package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Counts again, at every start, what each account stores from its rows, and sets right any total
 * that has drifted from them: one a bug miscounted, or one a release from before the total existed
 * left behind after being run against this database. Each account's row is locked while it is
 * counted, so a change committing meanwhile is counted once, either way.
 */
@Component
public class StorageRecount {

  private static final Logger log = LoggerFactory.getLogger(StorageRecount.class);

  private final UserRepository users;
  private final TransactionTemplate transactions;

  public StorageRecount(UserRepository users, PlatformTransactionManager transactionManager) {
    this.users = users;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** Never throws: a recount that fails leaves the totals as they were. */
  @PostConstruct
  public void recount() {
    int fixed = 0;
    try {
      // One query finds the accounts that look off; each is then counted again under its lock,
      // since one may only have looked off because a change was committing.
      for (Long id : users.findDriftedIds()) {
        if (Boolean.TRUE.equals(transactions.execute(status -> recount(id)))) {
          fixed++;
        }
      }
    } catch (RuntimeException e) {
      log.warn("Could not recount what the accounts store: {}", e.toString());
      return;
    }
    if (fixed > 0) {
      log.warn("Set right what {} accounts store, which had drifted from their files", fixed);
    }
  }

  private boolean recount(long id) {
    if (users.lockById(id).isEmpty()) {
      return false;
    }
    long counted = users.findUsedBytes(id);
    long stored = users.countStoredBytes(id);
    if (counted == stored) {
      return false;
    }
    log.warn(
        "Account {} was counted as storing {} bytes; its files add up to {}", id, counted, stored);
    users.setUsedBytes(id, stored);
    return true;
  }
}
