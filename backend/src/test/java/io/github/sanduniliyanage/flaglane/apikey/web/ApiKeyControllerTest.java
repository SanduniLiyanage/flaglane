package io.github.sanduniliyanage.flaglane.apikey.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sanduniliyanage.flaglane.ManagementApiSliceTest;
import io.github.sanduniliyanage.flaglane.apikey.domain.ApiKey;
import io.github.sanduniliyanage.flaglane.apikey.domain.IssuedApiKey;
import io.github.sanduniliyanage.flaglane.apikey.domain.KeyType;
import io.github.sanduniliyanage.flaglane.apikey.service.ApiKeyService;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.OwnerScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantResolver;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantScopes;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@ManagementApiSliceTest(ApiKeyController.class)
class ApiKeyControllerTest {

  private static final UUID USER = UUID.fromString("0f6b3b5e-7c2a-4f5e-9d61-2a4b8f0e1c3d");
  private static final String KEYS = "/api/projects/storefront/environments/production/keys";
  private static final Instant NOW = Instant.parse("2026-10-05T09:30:00Z");

  private final MockMvc mvc;
  private final OwnerScope owner = TenantScopes.owner(USER);
  private final ProjectScope project = TenantScopes.project(UUID.randomUUID(), "storefront", USER);
  private final EnvironmentScope production =
      TenantScopes.environment(project, UUID.randomUUID(), "production");

  @MockitoBean private ApiKeyService keys;
  @MockitoBean private TenantResolver tenants;

  ApiKeyControllerTest(@Autowired MockMvc mvc) {
    this.mvc = mvc;
  }

  @BeforeEach
  void resolvesTheEnvironment() {
    when(tenants.project(owner, "storefront")).thenReturn(project);
    when(tenants.environment(project, "production")).thenReturn(production);
  }

  @Test
  void issuingReturnsTheKeyThisOnce() throws Exception {
    ApiKey key =
        new ApiKey(
            UUID.randomUUID(), "checkout", KeyType.SERVER, "flg_srv_abcdefgh", NOW, null, null);
    when(keys.issue(production, "checkout", KeyType.SERVER))
        .thenReturn(new IssuedApiKey(key, "flg_srv_abcdefgh" + "x".repeat(35)));

    mvc.perform(
            post(KEYS)
                .with(signedIn())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"checkout\",\"type\":\"server\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.key").value("flg_srv_abcdefgh" + "x".repeat(35)))
        .andExpect(jsonPath("$.type").value("server"))
        .andExpect(jsonPath("$.prefix").value("flg_srv_abcdefgh"));
  }

  @Test
  void listingCarriesNoKeyAndNoHash() throws Exception {
    when(keys.list(production))
        .thenReturn(
            List.of(
                new ApiKey(
                    UUID.randomUUID(),
                    "checkout",
                    KeyType.CLIENT,
                    "flg_cli_abcdefgh",
                    NOW,
                    NOW,
                    null)));

    mvc.perform(get(KEYS).with(signedIn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].prefix").value("flg_cli_abcdefgh"))
        .andExpect(jsonPath("$[0].type").value("client"))
        .andExpect(jsonPath("$[0].key").doesNotExist())
        .andExpect(jsonPath("$[0].keyHash").doesNotExist());
  }

  @Test
  void anUnknownKeyTypeIsABadRequest() throws Exception {
    mvc.perform(
            post(KEYS)
                .with(signedIn())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"checkout\",\"type\":\"admin\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(keys);
  }

  @Test
  void revokingIsNoContent() throws Exception {
    UUID keyId = UUID.randomUUID();

    mvc.perform(delete(KEYS + "/" + keyId).with(signedIn())).andExpect(status().isNoContent());
    verify(keys).revoke(production, keyId);
  }

  @Test
  void aKeyIdThatIsNotAUuidIsABadRequest() throws Exception {
    mvc.perform(delete(KEYS + "/not-a-uuid").with(signedIn())).andExpect(status().isBadRequest());
    verify(keys, never()).revoke(any(), any());
  }

  private static RequestPostProcessor signedIn() {
    return jwt().jwt(token -> token.subject(USER.toString()));
  }
}
