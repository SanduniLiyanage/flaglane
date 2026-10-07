package io.github.sanduniliyanage.flaglane;

import io.github.sanduniliyanage.flaglane.common.clock.ClockConfiguration;
import io.github.sanduniliyanage.flaglane.common.security.JwtConfiguration;
import io.github.sanduniliyanage.flaglane.common.security.SecurityConfiguration;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantContext;
import io.github.sanduniliyanage.flaglane.serving.web.SdkRateLimitConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

/**
 * A {@code @WebMvcTest} of {@code /api/**} controllers with the real filter chains, JWT
 * verification and tenant interceptor. The test supplies its services, and a {@code
 * TenantResolver}, as mocks. The serving API's rate limit is left out: it guards {@code /sdk/**},
 * which no slice serves, and reads the ruleset cache, which no slice has.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@WebMvcTest(
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = SdkRateLimitConfiguration.class))
@Import({
  SecurityConfiguration.class,
  JwtConfiguration.class,
  ClockConfiguration.class,
  TenantContext.class
})
public @interface ManagementApiSliceTest {

  /** The controllers under test. */
  @AliasFor(annotation = WebMvcTest.class, attribute = "controllers")
  Class<?>[] value() default {};
}
