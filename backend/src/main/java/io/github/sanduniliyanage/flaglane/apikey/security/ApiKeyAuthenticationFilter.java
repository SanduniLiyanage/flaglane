package io.github.sanduniliyanage.flaglane.apikey.security;

import io.github.sanduniliyanage.flaglane.apikey.domain.SdkCredential;
import io.github.sanduniliyanage.flaglane.apikey.service.ApiKeyCache;
import io.github.sanduniliyanage.flaglane.apikey.service.LastUsedRecorder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code /sdk/**} by {@code Authorization: Bearer <api key>}, from the in-memory key
 * cache alone: no request performs a database query (FR-KEY-007, NFR-PER-004). A missing,
 * malformed, unknown or revoked key is a 401 that does not say which (docs/API.md).
 *
 * <p>Not a bean: Spring Boot registers every {@code Filter} bean for every path, and this one
 * belongs to the {@code /sdk/**} chain only.
 */
final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

  static final String UNAUTHORIZED_BODY =
      "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
          + "\"detail\":\"Missing, malformed or revoked API key\"}";

  private static final String BEARER = "Bearer ";

  private final ApiKeyCache keys;
  private final LastUsedRecorder lastUsed;

  ApiKeyAuthenticationFilter(ApiKeyCache keys, LastUsedRecorder lastUsed) {
    this.keys = keys;
    this.lastUsed = lastUsed;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<SdkCredential> credential = presentedKey(request).flatMap(keys::authenticate);
    if (credential.isEmpty()) {
      reject(response);
      return;
    }
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new SdkAuthentication(credential.get()));
    SecurityContextHolder.setContext(context);
    lastUsed.record(credential.get().keyId());
    chain.doFilter(request, response);
  }

  private static Optional<String> presentedKey(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      return Optional.empty();
    }
    return Optional.of(header.substring(BEARER.length()).strip());
  }

  private static void reject(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(UNAUTHORIZED_BODY);
  }
}
