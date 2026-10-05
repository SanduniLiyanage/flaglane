package io.github.sanduniliyanage.flaglane.account.service;

import io.github.sanduniliyanage.flaglane.account.domain.Account;
import io.github.sanduniliyanage.flaglane.account.domain.EmailAddress;
import io.github.sanduniliyanage.flaglane.account.domain.PasswordPolicy;
import io.github.sanduniliyanage.flaglane.account.persistence.UserEntity;
import io.github.sanduniliyanage.flaglane.account.persistence.UserRepository;
import io.github.sanduniliyanage.flaglane.common.errors.ConflictException;
import io.github.sanduniliyanage.flaglane.common.errors.CredentialsRejectedException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and sign-in (FR-ACC-001, FR-ACC-002).
 *
 * <p>Registration writes no audit entry. {@code audit_entries.project_id} is not nullable and the
 * action vocabulary has no account event, because FR-AUD-001's audit trail is the record of changes
 * to projects; an account belongs to none (ADR-021).
 */
@Service
public class AccountService {

  static final String DUPLICATE_EMAIL = "An account with this email address already exists";
  static final String REJECTED = "Email or password is incorrect";

  private final UserRepository users;
  private final PasswordEncoder passwords;
  private final TokenIssuer tokens;
  private final Clock clock;

  /**
   * A hash of a random password, compared against when no account has the email given. Without it,
   * an unknown email answers in microseconds and a known one in the time bcrypt takes, and the
   * difference says which addresses have accounts.
   */
  private final String unknownAccountHash;

  public AccountService(
      UserRepository users, PasswordEncoder passwords, TokenIssuer tokens, Clock clock) {
    this.users = users;
    this.passwords = passwords;
    this.tokens = tokens;
    this.clock = clock;
    this.unknownAccountHash = passwords.encode(UUID.randomUUID().toString());
  }

  /**
   * @param password must satisfy {@link PasswordPolicy}; the web layer has already checked
   * @throws ConflictException if an account already uses this email address
   */
  @Transactional
  public Account register(String email, String password, String displayName) {
    PasswordPolicy.violation(password)
        .ifPresent(
            problem -> {
              throw new IllegalArgumentException(problem);
            });
    String normalizedEmail = EmailAddress.normalize(email);
    if (users.existsByEmail(normalizedEmail)) {
      throw new ConflictException(DUPLICATE_EMAIL);
    }
    UserEntity user =
        new UserEntity(
            UUID.randomUUID(),
            normalizedEmail,
            passwords.encode(password),
            blankToNull(displayName),
            // PostgreSQL keeps microseconds; the value returned is the value stored.
            clock.instant().truncatedTo(ChronoUnit.MICROS));
    try {
      users.saveAndFlush(user);
    } catch (DataIntegrityViolationException e) {
      // Two registrations for one address raced past the check above; the constraint decided.
      throw new ConflictException(DUPLICATE_EMAIL, e);
    }
    return toAccount(user);
  }

  /**
   * @throws CredentialsRejectedException for an unknown email and a wrong password alike
   */
  @Transactional(readOnly = true)
  public IssuedToken signIn(String email, String password) {
    if (PasswordPolicy.exceedsMaxBytes(password)) {
      // No account can have this password, and checking it would mean truncating it.
      throw new CredentialsRejectedException(REJECTED);
    }
    Optional<UserEntity> user = users.findByEmail(EmailAddress.normalize(email));
    String hash = user.map(UserEntity::getPasswordHash).orElse(unknownAccountHash);
    boolean matches = passwords.matches(password, hash);
    if (user.isEmpty() || !matches) {
      throw new CredentialsRejectedException(REJECTED);
    }
    return tokens.issue(user.get().id());
  }

  private static Account toAccount(UserEntity user) {
    return new Account(user.id(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
