package com.javadropbox.javadropbox.repository;

import com.javadropbox.javadropbox.model.User;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {
  Optional<User> findByUsername(String username);

  boolean existsByUsername(String username);

  /** The account setup created, which owns whatever was stored before accounts had folders. */
  Optional<User> findFirstByOrderByIdAsc();

  List<User> findAllByOrderByUsernameAsc();

  /**
   * The admins who can sign in, locked until the transaction ends, so that two admins taking each
   * other's rights away at once cannot leave nobody to manage the accounts.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from User u where u.role = '" + User.ROLE_ADMIN + "' and u.enabled = true")
  List<User> lockEnabledAdmins();
}
