package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.model.FileHistory;
import com.javadropbox.javadropbox.model.FileHistory.ChangeType;
import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Writes the audit log of file operations. */
@Service
public class FileHistoryService {

  private static final Logger log = LoggerFactory.getLogger(FileHistoryService.class);

  private final FileHistoryRepository repository;
  private final TransactionTemplate separateTransaction;

  public FileHistoryService(
      FileHistoryRepository repository, PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.separateTransaction = new TransactionTemplate(transactionManager);
    separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Records a successful change as part of the caller's transaction, so it commits with it. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordSuccess(FileMetadata file, ChangeType changeType, User user, String details) {
    repository.save(FileHistory.success(file, changeType, user).withDetails(details));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void recordDeletion(String path, String filename, User user) {
    repository.save(FileHistory.success(path, filename, ChangeType.DELETE, user));
  }

  /**
   * Records a failed operation in a transaction of its own: the operation's transaction is being
   * rolled back, and the failure must not be rolled back with it.
   *
   * <p>Never throws: the caller is about to rethrow the original error, and a problem writing the
   * audit entry must not replace it.
   */
  public void recordFailure(
      String path, String filename, ChangeType changeType, User user, Exception cause) {
    String message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName();
    // Programmatic rather than @Transactional so the catch also covers the commit, which is where
    // a rejected insert surfaces.
    try {
      separateTransaction.executeWithoutResult(
          status ->
              repository.save(FileHistory.failure(path, filename, changeType, user, message)));
    } catch (RuntimeException e) {
      log.error("Could not record a failed {} of {}", changeType, path, e);
    }
  }
}
