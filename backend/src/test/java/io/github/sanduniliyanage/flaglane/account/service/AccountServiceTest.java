package io.github.sanduniliyanage.flaglane.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sanduniliyanage.flaglane.account.domain.Account;
import io.github.sanduniliyanage.flaglane.account.persistence.UserEntity;
import io.github.sanduniliyanage.flaglane.account.persistence.UserRepository;
import io.github.sanduniliyanage.flaglane.common.errors.ConflictException;
import io.github.sanduniliyanage.flaglane.common.errors.CredentialsRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

class AccountServiceTest {

  private static final String PASSWORD = "correct-horse-battery";
  private static final Instant NOW = Instant.parse("2026-10-05T09:30:00.123456789Z");

  private final UserRepository users = mock(UserRepository.class);
  private final TokenIssuer tokens = mock(TokenIssuer.class);
  private final PasswordEncoder passwords =
      spy(PasswordEncoderFactories.createDelegatingPasswordEncoder());
  private AccountService accounts;

  @BeforeEach
  void createService() {
    accounts = new AccountService(users, passwords, tokens, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void registrationStoresANormalisedEmailAndABcryptHashNeverThePassword() {
    Account account = accounts.register("  Amara@Example.COM ", PASSWORD, " Amara ");

    UserEntity saved = savedUser();
    assertThat(saved.getEmail()).isEqualTo("amara@example.com");
    assertThat(saved.getPasswordHash()).startsWith("{bcrypt}$2").doesNotContain(PASSWORD);
    assertThat(passwords.matches(PASSWORD, saved.getPasswordHash())).isTrue();
    assertThat(account.email()).isEqualTo("amara@example.com");
    assertThat(account.displayName()).isEqualTo("Amara");
    assertThat(account.id()).isEqualTo(saved.id());
  }

  @Test
  void registrationTimeComesFromTheClockAtTheDatabasesPrecision() {
    Account account = accounts.register("amara@example.com", PASSWORD, null);

    assertThat(account.createdAt()).isEqualTo(Instant.parse("2026-10-05T09:30:00.123456Z"));
  }

  @Test
  void aBlankDisplayNameIsNoDisplayName() {
    Account account = accounts.register("amara@example.com", PASSWORD, "   ");

    assertThat(account.displayName()).isNull();
  }

  @Test
  void anEmailAlreadyRegisteredInAnyCaseIsAConflict() {
    when(users.existsByEmail("amara@example.com")).thenReturn(true);

    assertThatExceptionOfType(ConflictException.class)
        .isThrownBy(() -> accounts.register("AMARA@example.com", PASSWORD, null))
        .withMessage(AccountService.DUPLICATE_EMAIL);
    verify(users, never()).saveAndFlush(any());
  }

  @Test
  void aRegistrationThatLosesARaceForItsEmailIsAConflict() {
    when(users.saveAndFlush(any()))
        .thenThrow(new DataIntegrityViolationException("uq_users_email"));

    assertThatExceptionOfType(ConflictException.class)
        .isThrownBy(() -> accounts.register("amara@example.com", PASSWORD, null))
        .withMessage(AccountService.DUPLICATE_EMAIL);
  }

  @Test
  void aPasswordThePolicyRefusesNeverReachesTheDatabase() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> accounts.register("amara@example.com", "too-short", null));
    verify(users, never()).saveAndFlush(any());
  }

  @Test
  void signInWithTheRightPasswordIssuesATokenForThatUser() {
    UserEntity user = existingUser();
    IssuedToken token = new IssuedToken("jwt", NOW.plusSeconds(8 * 3600));
    when(tokens.issue(user.id())).thenReturn(token);

    IssuedToken issued = accounts.signIn(" Amara@Example.com", PASSWORD);

    assertThat(issued).isSameAs(token);
  }

  @Test
  void aWrongPasswordIsRejected() {
    existingUser();

    assertThatExceptionOfType(CredentialsRejectedException.class)
        .isThrownBy(() -> accounts.signIn("amara@example.com", "wrong-horse-battery"))
        .withMessage(AccountService.REJECTED);
    verify(tokens, never()).issue(any());
  }

  @Test
  void anUnknownEmailIsRejectedExactlyLikeAWrongPassword() {
    when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

    assertThatExceptionOfType(CredentialsRejectedException.class)
        .isThrownBy(() -> accounts.signIn("nobody@example.com", PASSWORD))
        .withMessage(AccountService.REJECTED);
  }

  @Test
  void anUnknownEmailStillCostsOneBcryptComparison() {
    when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

    assertThatExceptionOfType(CredentialsRejectedException.class)
        .isThrownBy(() -> accounts.signIn("nobody@example.com", PASSWORD));

    ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
    verify(passwords, times(1)).matches(any(), hash.capture());
    assertThat(hash.getValue()).startsWith("{bcrypt}$2");
  }

  @Test
  void aPasswordLongerThanBcryptReadsIsRejectedWithoutBeingTruncated() {
    UserEntity user = existingUser();
    String longer = PASSWORD + "x".repeat(72);

    assertThatExceptionOfType(CredentialsRejectedException.class)
        .isThrownBy(() -> accounts.signIn(user.getEmail(), longer));
    verify(tokens, never()).issue(any());
  }

  private UserEntity savedUser() {
    ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
    verify(users).saveAndFlush(saved.capture());
    return saved.getValue();
  }

  private UserEntity existingUser() {
    UserEntity user =
        new UserEntity(
            UUID.randomUUID(), "amara@example.com", passwords.encode(PASSWORD), null, NOW);
    when(users.findByEmail("amara@example.com")).thenReturn(Optional.of(user));
    return user;
  }
}
