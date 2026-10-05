package io.github.sanduniliyanage.flaglane;

import io.github.sanduniliyanage.flaglane.common.clock.ClockConfiguration;
import io.github.sanduniliyanage.flaglane.common.security.JwtConfiguration;
import io.github.sanduniliyanage.flaglane.common.security.SecurityConfiguration;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantContext;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

/**
 * A {@code @WebMvcTest} of {@code /api/**} controllers with the real filter chains, JWT
 * verification and tenant interceptor. The test supplies its services, and a {@code
 * TenantResolver}, as mocks.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@WebMvcTest
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
