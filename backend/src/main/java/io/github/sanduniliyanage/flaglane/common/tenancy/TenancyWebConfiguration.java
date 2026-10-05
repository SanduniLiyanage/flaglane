package io.github.sanduniliyanage.flaglane.common.tenancy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Resolves tenant scopes for every {@code /api/**} request and hands them to controllers that
 * declare an {@link OwnerScope}, {@link ProjectScope} or {@link EnvironmentScope} parameter.
 */
@Configuration(proxyBeanMethods = false)
public class TenancyWebConfiguration implements WebMvcConfigurer {

  private final TenantContext context;
  private final TenantResolver resolver;

  public TenancyWebConfiguration(TenantContext context, TenantResolver resolver) {
    this.context = context;
    this.resolver = resolver;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new TenantInterceptor(context, resolver)).addPathPatterns("/api/**");
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new ScopeArgumentResolver(context));
  }

  /** Supplies the scopes the interceptor resolved, and the owner from the security context. */
  static final class ScopeArgumentResolver implements HandlerMethodArgumentResolver {

    private final TenantContext context;

    ScopeArgumentResolver(TenantContext context) {
      this.context = context;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
      Class<?> type = parameter.getParameterType();
      return type == OwnerScope.class
          || type == ProjectScope.class
          || type == EnvironmentScope.class;
    }

    @Override
    public Object resolveArgument(
        MethodParameter parameter,
        ModelAndViewContainer mavContainer,
        NativeWebRequest webRequest,
        WebDataBinderFactory binderFactory) {
      Class<?> type = parameter.getParameterType();
      if (type == OwnerScope.class) {
        return context.owner();
      }
      HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
      String attribute =
          type == ProjectScope.class
              ? TenantInterceptor.PROJECT_SCOPE
              : TenantInterceptor.ENVIRONMENT_SCOPE;
      Object scope = request == null ? null : request.getAttribute(attribute);
      if (scope == null) {
        throw new IllegalStateException(
            parameter.getExecutable()
                + " takes a "
                + type.getSimpleName()
                + " but its path names no "
                + (type == ProjectScope.class ? "{projectKey}" : "{envKey}"));
      }
      return scope;
    }
  }
}
