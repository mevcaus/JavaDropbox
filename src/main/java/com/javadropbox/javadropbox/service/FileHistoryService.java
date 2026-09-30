package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.dto.FileHistoryDto;
import com.javadropbox.javadropbox.dto.HistoryPage;
import com.javadropbox.javadropbox.exception.BadRequestException;
import com.javadropbox.javadropbox.exception.ConflictException;
import com.javadropbox.javadropbox.exception.ForbiddenException;
import com.javadropbox.javadropbox.exception.NotFoundException;
import com.javadropbox.javadropbox.model.FileHistory;
import com.javadropbox.javadropbox.model.FileHistory.ChangeType;
import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.model.User;
import com.javadropbox.javadropbox.repository.FileHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Writes the audit log of file operations. */
@Service
public class FileHistoryService {

  public static final int MAX_PAGE_SIZE = 200;

  private static final Logger log = LoggerFactory.getLogger(FileHistoryService.class);

  private final FileHistoryRepository repository;
  private final TransactionTemplate separateTransaction;

  public FileHistoryService(
      FileHistoryRepository repository, PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.separateTransaction = new TransactionTemplate(transactionManager);
    separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** A page of the history, newest first; the table grows forever, so it is never sent whole. */
  @Transactional(readOnly = true)
  public HistoryPage page(int page, int size) {
    if (page < 0 || size < 1) {
      throw new BadRequestException("page must be 0 or more and size at least 1");
    }
    int pageSize = Math.min(size, MAX_PAGE_SIZE);
    // The query's offset is an int; a page beyond it could never hold anything anyway.
    if ((long) page * pageSize > Integer.MAX_VALUE) {
      throw new BadRequestException("page is out of range");
    }
    PageRequest request =
        PageRequest.of(
            page, pageSize, Sort.by(Sort.Order.desc("timestamp"), Sort.Order.desc("id")));
    Page<FileHistory> result = repository.findAll(request);
    return new HistoryPage(
        result.getContent().stream().map(FileHistoryDto::fromEntity).toList(),
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages());
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
    String message = clientSafeMessage(cause);
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

  // The history is shown to clients, so it gets what the API would answer them: the messages of
  // our own 4xx exceptions, which are written for them, and a generic text otherwise -- a disk
  // error's message carries absolute paths. The caller rethrows the exception, and the exception
  // handler (or the servlet container) logs the details.
  private static String clientSafeMessage(Exception cause) {
    if (cause instanceof BadRequestException
        || cause instanceof NotFoundException
        || cause instanceof ConflictException
        || cause instanceof ForbiddenException) {
      return cause.getMessage();
    }
    if (cause instanceof DataIntegrityViolationException
        || cause instanceof ConcurrencyFailureException) {
      return "It conflicted with another change.";
    }
    return "The operation could not be completed.";
  }
}
