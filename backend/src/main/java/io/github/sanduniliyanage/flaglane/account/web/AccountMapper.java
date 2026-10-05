package io.github.sanduniliyanage.flaglane.account.web;

import io.github.sanduniliyanage.flaglane.account.domain.Account;
import io.github.sanduniliyanage.flaglane.account.service.IssuedToken;

/** Service results to response bodies. */
final class AccountMapper {

  static final String BEARER = "Bearer";

  private AccountMapper() {}

  static AccountResponse toResponse(Account account) {
    return new AccountResponse(
        account.id(), account.email(), account.displayName(), account.createdAt());
  }

  static AccessTokenResponse toResponse(IssuedToken token) {
    return new AccessTokenResponse(token.value(), BEARER, token.expiresAt());
  }
}
