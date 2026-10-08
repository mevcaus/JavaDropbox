package com.javadropbox.javadropbox.repository;

import com.javadropbox.javadropbox.model.FileMetadata;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;

class FileMetadataLockingImpl implements FileMetadataLocking {

  @PersistenceContext private EntityManager entityManager;

  @Override
  public Optional<FileMetadata> lockById(Long id) {
    FileMetadata row = entityManager.find(FileMetadata.class, id);
    if (row == null) {
      return Optional.empty();
    }
    // A refresh rather than a locking query: the row can already be in the persistence context
    // (loaded earlier in the same request), and a query would lock it but keep the old state.
    try {
      entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE);
    } catch (EntityNotFoundException e) {
      return Optional.empty();
    }
    return Optional.of(row);
  }

  @Override
  public Optional<FileMetadata> lockByPath(Long ownerId, String path) {
    return entityManager
        .createQuery(
            "select m.id from FileMetadata m where m.owner.id = :ownerId and m.path = :path",
            Long.class)
        .setParameter("ownerId", ownerId)
        .setParameter("path", path)
        .getResultStream()
        .findFirst()
        .flatMap(this::lockById);
  }
}
