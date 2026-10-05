package io.github.sanduniliyanage.flaglane.account.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sanduniliyanage.flaglane.ManagementApiSliceTest;
import io.github.sanduniliyanage.flaglane.account.domain.Account;
import io.github.sanduniliyanage.flaglane.account.service.AccountService;
import io.github.sanduniliyanage.flaglane.account.service.IssuedToken;
import io.github.sanduniliyanage.flaglane.common.errors.ConflictException;
import io.github.sanduniliyanage.flaglane.common.errors.CredentialsRejectedException;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantResolver;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/** Status codes, validation and serialisation of the two open {@code /api/auth} endpoints. */
@ManagementApiSliceTest(AuthController.class)
class AuthControllerTest {

  private static final String PASSWORD = "correct-horse-battery";
  private static final UUID ID = UUID.fromString("0f6b3b5e-7c2a-4f5e-9d61-2a4b8f0e1c3d");
  private static final Instant CREATED_AT = Instant.parse("2026-10-05T09:30:00.123456Z");

  private final MockMvc mvc;

  @MockitoBean private AccountService accounts;
  @MockitoBean private TenantResolver tenants;

  AuthControllerTest(@Autowired MockMvc mvc) {
    this.mvc = mvc;
  }

  @Test
  void registeringReturnsTheAccountWithoutAnyCredential() throws Exception {
    when(accounts.register("amara@example.com", PASSWORD, "Amara"))
        .thenReturn(new Account(ID, "amara@example.com", "Amara", CREATED_AT));

    mvc.perform(
            register(
                "{\"email\":\"amara@example.com\",\"password\":\""
                    + PASSWORD
                    + "\",\"displayName\":\"Amara\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(ID.toString()))
        .andExpect(jsonPath("$.email").value("amara@example.com"))
        .andExpect(jsonPath("$.displayName").value("Amara"))
        .andExpect(jsonPath("$.createdAt").value("2026-10-05T09:30:00.123456Z"))
        .andExpect(jsonPath("$.password").doesNotExist())
        .andExpect(jsonPath("$.passwordHash").doesNotExist());
  }

  @Test
  void aMalformedEmailIsABadRequestNamingTheField() throws Exception {
    mvc.perform(register("{\"email\":\"not-an-email\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors[0].field").value("email"));
    verifyNoInteractions(accounts);
  }

  @Test
  void aShortPasswordIsABadRequestThatNeverEchoesThePassword() throws Exception {
    mvc.perform(register("{\"email\":\"amara@example.com\",\"password\":\"fourteen-chars\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("password"))
        .andExpect(jsonPath("$.errors[0].message").value("password must be at least 15 characters"))
        .andExpect(content().string(not(containsString("fourteen-chars"))));
    verifyNoInteractions(accounts);
  }

  @Test
  void aPasswordLongerThanBcryptReadsIsABadRequest() throws Exception {
    mvc.perform(
            register("{\"email\":\"amara@example.com\",\"password\":\"" + "a".repeat(73) + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors[0].message").value("password must be at most 72 bytes in UTF-8"));
  }

  @Test
  void aBodyThatIsNotJsonIsABadRequest() throws Exception {
    mvc.perform(register("email=amara@example.com"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void anEmailAlreadyInUseIsAConflict() throws Exception {
    when(accounts.register(any(), any(), any()))
        .thenThrow(new ConflictException("An account with this email address already exists"));

    mvc.perform(register("{\"email\":\"amara@example.com\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("An account with this email address already exists"));
  }

  @Test
  void signingInReturnsABearerTokenAndItsExpiry() throws Exception {
    when(accounts.signIn("amara@example.com", PASSWORD))
        .thenReturn(
            new IssuedToken("header.payload.signature", Instant.parse("2026-10-05T17:30:00Z")));

    mvc.perform(login("{\"email\":\"amara@example.com\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("header.payload.signature"))
        .andExpect(jsonPath("$.tokenType").value("Bearer"))
        .andExpect(jsonPath("$.expiresAt").value("2026-10-05T17:30:00Z"))
        .andExpect(jsonPath("$.refreshToken").doesNotExist());
  }

  @Test
  void rejectedCredentialsAreUnauthorizedWithOneMessageForEveryCause() throws Exception {
    when(accounts.signIn(any(), any()))
        .thenThrow(new CredentialsRejectedException("Email or password is incorrect"));

    mvc.perform(login("{\"email\":\"amara@example.com\",\"password\":\"wrong-horse-battery\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("Email or password is incorrect"));
  }

  @Test
  void signInNeedsBothFields() throws Exception {
    mvc.perform(login("{\"email\":\"amara@example.com\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("password"));
    verifyNoInteractions(accounts);
  }

  @Test
  void everyOtherApiEndpointWantsABearerToken() throws Exception {
    mvc.perform(get("/api/projects"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", containsString("Bearer")));
  }

  private static RequestBuilder register(String json) {
    return post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json);
  }

  private static RequestBuilder login(String json) {
    return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json);
  }
}
