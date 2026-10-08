package com.javadropbox.javadropbox.repository;

import com.javadropbox.javadropbox.model.AccountLink;
import com.javadropbox.javadropbox.model.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AccountLinkRepository extends JpaRepository<AccountLink, Long> {

  /** The link with this token hash, with the account a reset is for. */
  @Query("select l from AccountLink l left join fetch l.user where l.tokenHash = :tokenHash")
  Optional<AccountLink> findByTokenHash(String tokenHash);

  /** Invitations that can still be used at {@code now}, the newest first. */
  @Query(
      "select l from AccountLink l left join fetch l.createdBy where l.purpose = 'INVITE'"
          + " and l.expiresAt > :now order by l.createdAt desc, l.id desc")
  List<AccountLink> findOpenInvites(Instant now);

  /** Removes the invitation for {@code username}, if there is one, to make way for a new one. */
  @Modifying
  @Query("delete from AccountLink l where l.purpose = 'INVITE' and l.username = :username")
  void deleteInvite(String username);

  /** Removes the password reset for {@code user}, if there is one. */
  @Modifying
  @Query("delete from AccountLink l where l.purpose = 'PASSWORD_RESET' and l.user = :user")
  void deletePasswordReset(User user);

  /** Removes links that expired before {@code time}; they can never be used again. */
  @Modifying
  @Query("delete from AccountLink l where l.expiresAt < :time")
  void deleteExpiredBefore(Instant time);
}
