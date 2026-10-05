package io.github.sanduniliyanage.flaglane.apikey.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Marks every {@code /sdk/**} response private to the key that asked for it: {@code Cache-Control:
 * private, no-store} and {@code Vary: Authorization}, set before anything else writes to the
 * response, so it holds for 401s and errors as well (FR-SRV-001).
 *
 * <p>Set first rather than by Spring Security's header writers, which write on commit and skip a
 * {@code Vary} that something earlier has already set; Spring's CORS handling always has.
 */
final class PrivateResponseFilter extends OncePerRequestFilter {

  static final String CACHE_CONTROL = "private, no-store";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    response.addHeader(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION);
    chain.doFilter(request, response);
  }
}
