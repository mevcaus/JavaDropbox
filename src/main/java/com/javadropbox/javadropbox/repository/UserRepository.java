package com.javadropbox.javadropbox.repository;

import com.javadropbox.javadropbox.model.User;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {
  Optional<User> findByUsername(String username);

  boolean existsByUsername(String username);

  /** The account setup created, which owns whatever was stored before accounts had folders. */
  Optional<User> findFirstByOrderByIdAsc();

  List<User> findAllByOrderByUsernameAsc();

  /** What the account stores now, read from the database rather than a loaded account. */
  @Query("select u.usedBytes from User u where u.id = :id")
  long findUsedBytes(Long id);

  /**
   * Adds {@code delta} bytes to what the account stores, only if that still fits its quota: the
   * charge and the check are one statement, and the row stays locked until the transaction ends, so
   * of two changes at once the second sees the first.
   *
   * @return 1 if it fit, 0 if it would go over the quota
   */
  @Modifying
  @Query(
      value =
          "update users set used_bytes = used_bytes + :delta where id = :id"
              + " and (quota_bytes is null or used_bytes + :delta <= quota_bytes)",
      nativeQuery = true)
  int chargeWithinQuota(Long id, long delta);

  /** The account's row, locked until the transaction ends. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from User u where u.id = :id")
  Optional<User> lockById(Long id);

  /** The accounts whose total differs from what their rows add up to. */
  @Query(
      value =
          "select u.id from users u where u.used_bytes <>"
              + " coalesce((select sum(m.size) from file_metadata m"
              + " where m.user_id = u.id and m.is_directory = false), 0)"
              + " + coalesce((select sum(v.size) from file_versions v"
              + " join file_metadata m on m.id = v.file_id where m.user_id = u.id), 0)",
      nativeQuery = true)
  List<Long> findDriftedIds();

  /** What the account's rows add up to: its files and their previous versions. */
  @Query(
      value =
          "select coalesce((select sum(m.size) from file_metadata m"
              + " where m.user_id = :id and m.is_directory = false), 0)"
              + " + coalesce((select sum(v.size) from file_versions v"
              + " join file_metadata m on m.id = v.file_id where m.user_id = :id), 0)",
      nativeQuery = true)
  long countStoredBytes(Long id);

  @Modifying
  @Query(value = "update users set used_bytes = :bytes where id = :id", nativeQuery = true)
  int setUsedBytes(Long id, long bytes);

  /** Adds {@code delta} bytes, which may be negative, to what the account stores. */
  @Modifying
  @Query(
      value = "update users set used_bytes = greatest(used_bytes + :delta, 0) where id = :id",
      nativeQuery = true)
  int adjustUsedBytes(Long id, long delta);

  /**
   * The admins who can sign in, locked until the transaction ends, so that two admins taking each
   * other's rights away at once cannot leave nobody to manage the accounts.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from User u where u.role = '" + User.ROLE_ADMIN + "' and u.enabled = true")
  List<User> lockEnabledAdmins();
}
