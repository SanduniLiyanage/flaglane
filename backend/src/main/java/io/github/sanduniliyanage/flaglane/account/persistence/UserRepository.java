package io.github.sanduniliyanage.flaglane.account.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Users by email. Exposes only what accounts need rather than every {@code JpaRepository} method,
 * so nothing can list or delete users by accident.
 */
public interface UserRepository extends Repository<UserEntity, UUID> {

  /** Inserts and flushes, so a duplicate email fails here, inside the service's transaction. */
  UserEntity saveAndFlush(UserEntity user);

  /**
   * @param email already normalised; see {@code EmailAddress}
   */
  Optional<UserEntity> findByEmail(String email);

  /**
   * @param email already normalised; see {@code EmailAddress}
   */
  boolean existsByEmail(String email);
}
