package com.javadropbox.javadropbox.repository;

import com.javadropbox.javadropbox.model.FileMetadata;
import com.javadropbox.javadropbox.model.ShareLink;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ShareLinkRepository extends JpaRepository<ShareLink, Long> {

  /** The link with this token hash, with the item it was made for. */
  @Query("select l from ShareLink l join fetch l.file where l.tokenHash = :tokenHash")
  Optional<ShareLink> findByTokenHash(String tokenHash);

  /** An item's links that still open at {@code now}, the soonest to expire first. */
  @Query(
      "select l from ShareLink l left join fetch l.createdBy where l.file = :file"
          + " and l.path = l.file.path and l.revokedAt is null and l.expiresAt > :now"
          + " order by l.expiresAt")
  List<ShareLink> findActive(FileMetadata file, Instant now);

  /** Removes an item's links, e.g. because a new item is taking over its metadata row. */
  @Modifying
  @Query("delete from ShareLink l where l.file = :file")
  void deleteByFile(FileMetadata file);

  /** Removes links that expired before {@code time}; they can never open again. */
  @Modifying
  @Query("delete from ShareLink l where l.expiresAt < :time")
  void deleteExpiredBefore(Instant time);
}
