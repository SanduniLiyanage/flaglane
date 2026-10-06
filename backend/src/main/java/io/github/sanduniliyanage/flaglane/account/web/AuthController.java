package io.github.sanduniliyanage.flaglane.account.web;

import io.github.sanduniliyanage.flaglane.account.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Registration and sign-in: the only {@code /api/**} endpoints open without a token. */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Accounts and access tokens for the dashboard")
public class AuthController {

  private static final String TOO_MANY =
      "Too many registration and sign-in requests from this address: 10 a minute by default,"
          + " shared by both endpoints (ADR-029)";
  private static final String RETRY_AFTER = "Seconds until this address may try again";

  private final AccountService accounts;

  public AuthController(AccountService accounts) {
    this.accounts = accounts;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Register an account",
      description =
          "Creates a dashboard account (FR-ACC-001). The password is stored as a bcrypt hash."
              + " Sign in afterwards to obtain a token.")
  @ApiResponse(responseCode = "201", description = "Registered")
  @ApiResponse(
      responseCode = "400",
      description = "Malformed email, or a password outside the policy",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "An account already uses this email address",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "429",
      description = TOO_MANY,
      headers = @Header(name = HttpHeaders.RETRY_AFTER, description = RETRY_AFTER),
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  AccountResponse register(@Valid @RequestBody RegisterRequest request) {
    return AccountMapper.toResponse(
        accounts.register(request.email(), request.password(), request.displayName()));
  }

  @PostMapping("/login")
  @Operation(
      summary = "Sign in",
      description =
          "Issues one access token valid for 8 hours (FR-ACC-002). There is no refresh token,"
              + " and signing out is discarding the token: nothing invalidates it server-side"
              + " before it expires (ADR-012).")
  @ApiResponse(responseCode = "200", description = "Signed in")
  @ApiResponse(
      responseCode = "401",
      description = "Unknown email or wrong password; the response does not say which",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "429",
      description = TOO_MANY,
      headers = @Header(name = HttpHeaders.RETRY_AFTER, description = RETRY_AFTER),
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  AccessTokenResponse login(@Valid @RequestBody LoginRequest request) {
    return AccountMapper.toResponse(accounts.signIn(request.email(), request.password()));
  }
}
