package io.github.sanduniliyanage.flaglane.account.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.sanduniliyanage.flaglane.FlaglaneIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ContextConfiguration;

/**
 * {@code users} through JPA, as the application role, against the migrated schema. Hibernate also
 * validates the entity mapping against that schema when this context starts.
 */
@DataJpaTest
@ContextConfiguration(initializers = FlaglaneIntegrationTest.SharedDatabase.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest {

  private static final Instant CREATED_AT = Instant.parse("2026-10-05T09:30:00.123456Z");

  private final UserRepository users;

  UserRepositoryTest(@Autowired UserRepository users) {
    this.users = users;
  }

  @Test
  void findsAUserByEmail() {
    UUID id = UUID.randomUUID();
    users.saveAndFlush(
        new UserEntity(id, "amara@example.com", "{bcrypt}hash", "Amara", CREATED_AT));

    UserEntity found = users.findByEmail("amara@example.com").orElseThrow();

    assertThat(found.id()).isEqualTo(id);
    assertThat(found.getPasswordHash()).isEqualTo("{bcrypt}hash");
    assertThat(found.getDisplayName()).isEqualTo("Amara");
    assertThat(found.getCreatedAt()).isEqualTo(CREATED_AT);
    assertThat(users.existsByEmail("amara@example.com")).isTrue();
  }

  @Test
  void anUnknownEmailFindsNobody() {
    assertThat(users.findByEmail("nobody@example.com")).isEmpty();
    assertThat(users.existsByEmail("nobody@example.com")).isFalse();
  }

  @Test
  void aDisplayNameIsOptional() {
    users.saveAndFlush(
        new UserEntity(UUID.randomUUID(), "anon@example.com", "h", null, CREATED_AT));

    assertThat(users.findByEmail("anon@example.com").orElseThrow().getDisplayName()).isNull();
  }

  @Test
  void theDatabaseRefusesASecondAccountForOneEmail() {
    users.saveAndFlush(new UserEntity(UUID.randomUUID(), "dup@example.com", "h", null, CREATED_AT));

    assertThatExceptionOfType(DataIntegrityViolationException.class)
        .isThrownBy(
            () ->
                users.saveAndFlush(
                    new UserEntity(UUID.randomUUID(), "dup@example.com", "h", null, CREATED_AT)));
  }

  @Test
  void aNewEntityIsInsertedNotMerged() {
    UserEntity user = new UserEntity(UUID.randomUUID(), "new@example.com", "h", null, CREATED_AT);

    assertThat(user.isNew()).isTrue();
    UserEntity saved = users.saveAndFlush(user);

    assertThat(saved).isSameAs(user);
    assertThat(saved.isNew()).isFalse();
  }
}
