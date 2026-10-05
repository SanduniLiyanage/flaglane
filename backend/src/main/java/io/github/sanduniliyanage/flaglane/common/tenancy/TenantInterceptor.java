package io.github.sanduniliyanage.flaglane.common.tenancy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Resolves the tenant scopes a {@code /api/**} path names, before the handler runs.
 *
 * <p>Any path carrying {@code {projectKey}} is checked against the signed-in user's projects, and
 * any carrying {@code {envKey}} against that project's environments, whether or not the controller
 * asks for the scope. Because this happens before the request body is read, another tenant's
 * project is a 404 even when the body would have been a 400: a validation error must not be the way
 * an attacker learns that a project exists.
 */
class TenantInterceptor implements HandlerInterceptor {

  static final String PROJECT_KEY = "projectKey";
  static final String ENVIRONMENT_KEY = "envKey";
  static final String PROJECT_SCOPE = TenantInterceptor.class.getName() + ".project";
  static final String ENVIRONMENT_SCOPE = TenantInterceptor.class.getName() + ".environment";

  private final TenantContext context;
  private final TenantResolver resolver;

  TenantInterceptor(TenantContext context, TenantResolver resolver) {
    this.context = context;
    this.resolver = resolver;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    @SuppressWarnings("unchecked")
    Map<String, String> variables =
        (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    if (variables == null || !variables.containsKey(PROJECT_KEY)) {
      return true;
    }
    ProjectScope project = resolver.project(context.owner(), variables.get(PROJECT_KEY));
    request.setAttribute(PROJECT_SCOPE, project);
    if (variables.containsKey(ENVIRONMENT_KEY)) {
      request.setAttribute(
          ENVIRONMENT_SCOPE, resolver.environment(project, variables.get(ENVIRONMENT_KEY)));
    }
    return true;
  }
}
