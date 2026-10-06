package io.github.sanduniliyanage.flaglane.audit.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sanduniliyanage.flaglane.ManagementApiSliceTest;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditCursor;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditEntry;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditPage;
import io.github.sanduniliyanage.flaglane.audit.service.AuditTrail;
import io.github.sanduniliyanage.flaglane.common.errors.NotFoundException;
import io.github.sanduniliyanage.flaglane.common.tenancy.OwnerScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantResolver;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantScopes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The audit endpoint's parameters, validation and response shape. */
@ManagementApiSliceTest(AuditController.class)
class AuditControllerTest {

  private static final UUID USER = UUID.fromString("0f6b3b5e-7c2a-4f5e-9d61-2a4b8f0e1c3d");
  private static final String AUDIT = "/api/projects/storefront/audit";
  private static final Instant AT = Instant.parse("2026-10-05T09:30:00.123456Z");
  private static final UUID ENTRY = UUID.fromString("8c1e0d6a-1111-4c2b-9a3d-5e6f7a8b9c0d");

  private final MockMvc mvc;
  private final OwnerScope owner = TenantScopes.owner(USER);
  private final ProjectScope project = TenantScopes.project(UUID.randomUUID(), "storefront", USER);

  @MockitoBean private AuditTrail trail;
  @MockitoBean private TenantResolver tenants;

  AuditControllerTest(@Autowired MockMvc mvc) {
    this.mvc = mvc;
  }

  @BeforeEach
  void resolvesTheProject() {
    when(tenants.project(owner, "storefront")).thenReturn(project);
  }

  @Test
  void aPageCarriesItsEntriesAndTheCursorForTheNext() throws Exception {
    AuditEntry entry =
        new AuditEntry(
            ENTRY,
            AT,
            "config.updated",
            "amara@example.com",
            "Amara",
            "qa",
            true,
            "new-checkout",
            Map.of("enabled", false),
            Map.of("enabled", true));
    when(trail.page(project, null, null, null, 50))
        .thenReturn(new AuditPage(List.of(entry), Optional.of(entry.cursor())));

    mvc.perform(get(AUDIT).with(signedIn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries[0].id").value(ENTRY.toString()))
        .andExpect(jsonPath("$.entries[0].createdAt").value("2026-10-05T09:30:00.123456Z"))
        .andExpect(jsonPath("$.entries[0].action").value("config.updated"))
        .andExpect(jsonPath("$.entries[0].actor.email").value("amara@example.com"))
        .andExpect(jsonPath("$.entries[0].actor.displayName").value("Amara"))
        .andExpect(jsonPath("$.entries[0].environment").value("qa"))
        .andExpect(jsonPath("$.entries[0].environmentDeleted").value(true))
        .andExpect(jsonPath("$.entries[0].flag").value("new-checkout"))
        .andExpect(jsonPath("$.entries[0].previousValue.enabled").value(false))
        .andExpect(jsonPath("$.entries[0].newValue.enabled").value(true))
        .andExpect(jsonPath("$.next").value("2026-10-05T09:30:00.123456Z," + ENTRY));
  }

  @Test
  void theLastPageSaysSoWithANullNext() throws Exception {
    when(trail.page(project, null, null, null, 50))
        .thenReturn(new AuditPage(List.of(), Optional.empty()));

    mvc.perform(get(AUDIT).with(signedIn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries").isEmpty())
        .andExpect(jsonPath("$.next").isEmpty());
  }

  @Test
  void filtersCursorAndLimitAreHandedOn() throws Exception {
    AuditCursor before = new AuditCursor(AT, ENTRY);
    when(trail.page(project, "production", "new-checkout", before, 10))
        .thenReturn(new AuditPage(List.of(), Optional.empty()));

    mvc.perform(
            get(AUDIT)
                .param("environment", "production")
                .param("flag", "new-checkout")
                .param("before", before.toString())
                .param("limit", "10")
                .with(signedIn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries").isEmpty());
  }

  @Test
  void aLimitOutsideOneToAHundredIsABadRequest() throws Exception {
    mvc.perform(get(AUDIT).param("limit", "0").with(signedIn())).andExpect(status().isBadRequest());
    mvc.perform(get(AUDIT).param("limit", "101").with(signedIn()))
        .andExpect(status().isBadRequest());
    mvc.perform(get(AUDIT).param("limit", "ten").with(signedIn()))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(trail);
  }

  @Test
  void aBeforeThatIsNotACursorIsABadRequest() throws Exception {
    for (String before :
        List.of("yesterday", "2026-10-05T09:30:00Z", "2026-10-05T09:30:00Z,not-a-uuid", ",")) {
      mvc.perform(get(AUDIT).param("before", before).with(signedIn()))
          .andExpect(status().isBadRequest());
    }

    verifyNoInteractions(trail);
  }

  @Test
  void anUnknownEnvironmentOrFlagIsNotFound() throws Exception {
    when(trail.page(eq(project), eq("qa"), isNull(), isNull(), anyInt()))
        .thenThrow(new NotFoundException("Environment not found"));

    mvc.perform(get(AUDIT).param("environment", "qa").with(signedIn()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("Environment not found"));
  }

  @Test
  void signingInIsRequired() throws Exception {
    mvc.perform(get(AUDIT)).andExpect(status().isUnauthorized());

    verifyNoInteractions(trail);
    verify(tenants, never()).project(any(), anyString());
  }

  private static RequestPostProcessor signedIn() {
    return jwt().jwt(token -> token.subject(USER.toString()));
  }
}
