package io.github.sanduniliyanage.flaglane.project.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import io.github.sanduniliyanage.flaglane.common.errors.ConflictException;
import io.github.sanduniliyanage.flaglane.common.errors.NotFoundException;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.OwnerScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantResolver;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantScopes;
import io.github.sanduniliyanage.flaglane.project.domain.Environment;
import io.github.sanduniliyanage.flaglane.project.domain.Project;
import io.github.sanduniliyanage.flaglane.project.service.EnvironmentService;
import io.github.sanduniliyanage.flaglane.project.service.ProjectService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Status codes, validation and scoping of the project and environment endpoints. */
@ManagementApiSliceTest({ProjectController.class, EnvironmentController.class})
class ProjectControllersTest {

  private static final UUID USER = UUID.fromString("0f6b3b5e-7c2a-4f5e-9d61-2a4b8f0e1c3d");
  private static final Instant CREATED_AT = Instant.parse("2026-10-05T09:30:00Z");

  private final MockMvc mvc;
  private final OwnerScope owner = TenantScopes.owner(USER);
  private final ProjectScope storefront =
      TenantScopes.project(UUID.randomUUID(), "storefront", USER);
  private final EnvironmentScope qa = TenantScopes.environment(storefront, UUID.randomUUID(), "qa");

  @MockitoBean private ProjectService projects;
  @MockitoBean private EnvironmentService environments;
  @MockitoBean private TenantResolver tenants;

  ProjectControllersTest(@Autowired MockMvc mvc) {
    this.mvc = mvc;
  }

  @Test
  void listsTheCallersProjects() throws Exception {
    when(projects.list(owner))
        .thenReturn(List.of(new Project("storefront", "Storefront", CREATED_AT)));

    mvc.perform(get("/api/projects").with(signedIn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].key").value("storefront"))
        .andExpect(jsonPath("$[0].id").doesNotExist());
  }

  @Test
  void createsAProject() throws Exception {
    when(projects.create(owner, "storefront", "Storefront"))
        .thenReturn(new Project("storefront", "Storefront", CREATED_AT));

    mvc.perform(json(post("/api/projects"), "{\"key\":\"storefront\",\"name\":\"Storefront\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.key").value("storefront"));
  }

  @Test
  void aProjectKeyOutsideTheUrlSafeFormatIsABadRequest() throws Exception {
    mvc.perform(json(post("/api/projects"), "{\"key\":\"Store Front\",\"name\":\"Storefront\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("key"));
    verifyNoInteractions(projects);
  }

  @Test
  void aKeyWithTheSaltSeparatorIsABadRequest() throws Exception {
    mvc.perform(json(post("/api/projects"), "{\"key\":\"store:front\",\"name\":\"Storefront\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aTakenProjectKeyIsAConflict() throws Exception {
    when(projects.create(any(), any(), any())).thenThrow(new ConflictException("taken"));

    mvc.perform(json(post("/api/projects"), "{\"key\":\"storefront\",\"name\":\"Storefront\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void listsAProjectsEnvironments() throws Exception {
    when(tenants.project(owner, "storefront")).thenReturn(storefront);
    when(environments.list(storefront))
        .thenReturn(List.of(new Environment("production", "Production", CREATED_AT)));

    mvc.perform(get("/api/projects/storefront/environments").with(signedIn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].key").value("production"));
  }

  @Test
  void createsAnEnvironmentInTheResolvedProject() throws Exception {
    when(tenants.project(owner, "storefront")).thenReturn(storefront);
    when(environments.create(storefront, "qa", "QA"))
        .thenReturn(new Environment("qa", "QA", CREATED_AT));

    mvc.perform(
            json(post("/api/projects/storefront/environments"), "{\"key\":\"qa\",\"name\":\"QA\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.key").value("qa"));
  }

  @Test
  void anotherUsersProjectIsNotFoundBeforeTheBodyIsEvenRead() throws Exception {
    when(tenants.project(owner, "theirs")).thenThrow(new NotFoundException("Project not found"));

    mvc.perform(json(post("/api/projects/theirs/environments"), "{\"key\":\"NOT VALID\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("Project not found"));
    verifyNoInteractions(environments);
  }

  @Test
  void deletesAnEnvironment() throws Exception {
    when(tenants.project(owner, "storefront")).thenReturn(storefront);
    when(tenants.environment(storefront, "qa")).thenReturn(qa);

    mvc.perform(delete("/api/projects/storefront/environments/qa").with(signedIn()))
        .andExpect(status().isNoContent());
    verify(environments).delete(qa);
  }

  @Test
  void anEnvironmentHoldingActiveKeysIsAConflict() throws Exception {
    when(tenants.project(owner, "storefront")).thenReturn(storefront);
    when(tenants.environment(storefront, "qa")).thenReturn(qa);
    doThrow(new ConflictException("has keys")).when(environments).delete(eq(qa));

    mvc.perform(delete("/api/projects/storefront/environments/qa").with(signedIn()))
        .andExpect(status().isConflict());
  }

  @Test
  void projectsNeedASignedInUser() throws Exception {
    mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/projects/storefront/environments")).andExpect(status().isUnauthorized());
    verifyNoInteractions(tenants);
  }

  private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
    return request.with(signedIn()).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static RequestPostProcessor signedIn() {
    return jwt().jwt(token -> token.subject(USER.toString()));
  }
}
