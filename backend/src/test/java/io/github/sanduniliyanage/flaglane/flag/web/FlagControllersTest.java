package io.github.sanduniliyanage.flaglane.flag.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sanduniliyanage.flaglane.ManagementApiSliceTest;
import io.github.sanduniliyanage.flaglane.common.errors.ConflictException;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.OwnerScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantResolver;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantScopes;
import io.github.sanduniliyanage.flaglane.flag.domain.ConfigChange;
import io.github.sanduniliyanage.flaglane.flag.domain.Flag;
import io.github.sanduniliyanage.flaglane.flag.domain.FlagConfiguration;
import io.github.sanduniliyanage.flaglane.flag.service.FlagConfigService;
import io.github.sanduniliyanage.flaglane.flag.service.FlagService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@ManagementApiSliceTest({FlagController.class, FlagConfigController.class})
class FlagControllersTest {

  private static final UUID USER = UUID.fromString("0f6b3b5e-7c2a-4f5e-9d61-2a4b8f0e1c3d");
  private static final Instant NOW = Instant.parse("2026-10-05T09:30:00Z");
  private static final String CONFIG = "/api/projects/storefront/flags/checkout/config/production";

  private final MockMvc mvc;
  private final OwnerScope owner = TenantScopes.owner(USER);
  private final ProjectScope project = TenantScopes.project(UUID.randomUUID(), "storefront", USER);
  private final EnvironmentScope production =
      TenantScopes.environment(project, UUID.randomUUID(), "production");

  @MockitoBean private FlagService flags;
  @MockitoBean private FlagConfigService configs;
  @MockitoBean private TenantResolver tenants;

  FlagControllersTest(@Autowired MockMvc mvc) {
    this.mvc = mvc;
  }

  @BeforeEach
  void resolvesTheScopes() {
    when(tenants.project(owner, "storefront")).thenReturn(project);
    when(tenants.environment(project, "production")).thenReturn(production);
  }

  @Test
  void aNewFlagIsHiddenFromClientKeysWhenVisibilityIsNotGiven() throws Exception {
    when(flags.create(project, "checkout", "Checkout", null, false))
        .thenReturn(new Flag("checkout", "Checkout", null, false, null, NOW));

    mvc.perform(
            json(
                post("/api/projects/storefront/flags"),
                "{\"key\":\"checkout\",\"name\":\"Checkout\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.clientSideVisible").value(false));
  }

  @Test
  void aFlagKeyOutsideTheUrlSafeFormatIsABadRequest() throws Exception {
    mvc.perform(
            json(post("/api/projects/storefront/flags"), "{\"key\":\"Check Out\",\"name\":\"x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("key"));
    verifyNoInteractions(flags);
  }

  @Test
  void archivingReturnsTheArchivedFlag() throws Exception {
    when(flags.archive(project, "checkout"))
        .thenReturn(new Flag("checkout", "Checkout", null, false, NOW, NOW));

    mvc.perform(post("/api/projects/storefront/flags/checkout/archive").with(signedIn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.archivedAt").value("2026-10-05T09:30:00Z"));
  }

  @Test
  void aConfigurationShowsItsRolloutAsAWholePercentage() throws Exception {
    when(configs.get(production, "checkout"))
        .thenReturn(
            new FlagConfiguration(
                "checkout", "production", true, false, false, 3_000, "checkout", NOW));

    mvc.perform(get(CONFIG).with(signedIn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rolloutPercentage").value(30))
        .andExpect(jsonPath("$.rolloutBasisPoints").doesNotExist())
        .andExpect(jsonPath("$.offValue").value(false));
  }

  @Test
  void aRolloutPercentageIsStoredAsBasisPoints() throws Exception {
    when(configs.update(eq(production), eq("checkout"), any()))
        .thenReturn(
            new FlagConfiguration(
                "checkout", "production", false, false, false, 2_500, "checkout", NOW));

    mvc.perform(json(patch(CONFIG), "{\"rolloutPercentage\":25}")).andExpect(status().isOk());

    verify(configs).update(production, "checkout", new ConfigChange(null, null, 2_500, null));
  }

  @Test
  void aRolloutAboveAHundredPercentIsABadRequest() throws Exception {
    mvc.perform(json(patch(CONFIG), "{\"rolloutPercentage\":101}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("rolloutPercentage"));
    verifyNoInteractions(configs);
  }

  @Test
  void aFractionalRolloutPercentageIsABadRequest() throws Exception {
    mvc.perform(json(patch(CONFIG), "{\"rolloutPercentage\":12.5}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(configs);
  }

  @Test
  void aSaltWithTheSeparatorIsABadRequest() throws Exception {
    mvc.perform(json(patch(CONFIG), "{\"rolloutSalt\":\"a:b\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(configs);
  }

  @Test
  void anOffValueInTheRequestChangesNothing() throws Exception {
    when(configs.update(eq(production), eq("checkout"), any()))
        .thenReturn(
            new FlagConfiguration(
                "checkout", "production", false, false, false, 0, "checkout", NOW));

    mvc.perform(json(patch(CONFIG), "{\"offValue\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.offValue").value(false));
    verify(configs).update(production, "checkout", new ConfigChange(null, null, null, null));
  }

  @Test
  void changingAnArchivedFlagIsAConflict() throws Exception {
    when(configs.update(eq(production), eq("checkout"), any()))
        .thenThrow(new ConflictException("archived"));

    mvc.perform(json(patch(CONFIG), "{\"enabled\":true}")).andExpect(status().isConflict());
  }

  private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
    return request.with(signedIn()).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static RequestPostProcessor signedIn() {
    return jwt().jwt(token -> token.subject(USER.toString()));
  }
}
