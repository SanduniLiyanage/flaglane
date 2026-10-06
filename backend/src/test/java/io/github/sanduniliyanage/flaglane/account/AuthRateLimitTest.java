package io.github.sanduniliyanage.flaglane.account;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sanduniliyanage.flaglane.FlaglaneIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Registration and sign-in are limited per client address (ADR-029), through the whole application:
 * filter chains, interceptor, exception handler. The limit is set to 3 a minute here, one request
 * every 20 seconds, so each test spends a fresh address's allowance and finishes long before it
 * refills; the bucket's arithmetic is {@code TokenBucketsTest}'s.
 */
@FlaglaneIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "flaglane.security.auth-rate-limit.per-minute=3")
class AuthRateLimitTest {

  private static final String PASSWORD = "correct-horse-battery";

  private final MockMvc mvc;
  private final ObjectMapper json;

  AuthRateLimitTest(@Autowired MockMvc mvc, @Autowired ObjectMapper json) {
    this.mvc = mvc;
    this.json = json;
  }

  @Test
  void theFourthAttemptFromOneAddressIsRefusedWithRetryAfterWhateverItsCredentials()
      throws Exception {
    String email = register("192.0.2.200");
    for (int i = 0; i < 3; i++) {
      login("192.0.2.10", email, "wrong-password-entirely").andExpect(status().isUnauthorized());
    }

    login("192.0.2.10", email, PASSWORD)
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "20"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(429))
        .andExpect(jsonPath("$.title").value("Too Many Requests"))
        .andExpect(jsonPath("$.detail", startsWith("Too many sign-in")));
  }

  @Test
  void registrationAndSignInShareOneAllowance() throws Exception {
    String email = register("192.0.2.20");
    register("192.0.2.20");
    login("192.0.2.20", email, PASSWORD).andExpect(status().isOk());

    mvc.perform(registration("192.0.2.20", uniqueEmail())).andExpect(status().isTooManyRequests());
  }

  @Test
  void anotherAddressKeepsItsOwnAllowance() throws Exception {
    String email = register("192.0.2.201");
    for (int i = 0; i < 4; i++) {
      login("192.0.2.30", email, "wrong-password-entirely");
    }

    login("192.0.2.31", email, PASSWORD).andExpect(status().isOk());
  }

  @Test
  void everyAddressInOneIpv6Slash64SharesAnAllowance() throws Exception {
    String email = register("192.0.2.202");
    login("2001:db8:40:1::1", email, PASSWORD).andExpect(status().isOk());
    login("2001:db8:40:1::2", email, PASSWORD).andExpect(status().isOk());
    login("2001:db8:40:1:ffff:ffff:ffff:fffe", email, PASSWORD).andExpect(status().isOk());

    login("2001:db8:40:1::4", email, PASSWORD).andExpect(status().isTooManyRequests());
    login("2001:db8:40:2::1", email, PASSWORD).andExpect(status().isOk());
  }

  @Test
  void theRestOfTheManagementApiIsNotLimited() throws Exception {
    String email = register("192.0.2.203");
    String token = accessToken(login("192.0.2.203", email, PASSWORD));
    for (int i = 0; i < 4; i++) {
      login("192.0.2.50", email, "wrong-password-entirely");
    }

    for (int i = 0; i < 5; i++) {
      mvc.perform(
              get("/api/projects")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                  .with(from("192.0.2.50")))
          .andExpect(status().isOk());
    }
  }

  private String register(String address) throws Exception {
    String email = uniqueEmail();
    mvc.perform(registration(address, email)).andExpect(status().isCreated());
    return email;
  }

  private MockHttpServletRequestBuilder registration(String address, String email)
      throws Exception {
    return post("/api/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD)))
        .with(from(address));
  }

  private ResultActions login(String address, String email, String password) throws Exception {
    return mvc.perform(
        post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", email, "password", password)))
            .with(from(address)));
  }

  private String accessToken(ResultActions signedIn) throws Exception {
    return json.readTree(signedIn.andReturn().getResponse().getContentAsString())
        .path("accessToken")
        .asText();
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor from(
      String address) {
    return request -> {
      request.setRemoteAddr(address);
      return request;
    };
  }

  private static String uniqueEmail() {
    return "limited-" + UUID.randomUUID() + "@example.com";
  }
}
