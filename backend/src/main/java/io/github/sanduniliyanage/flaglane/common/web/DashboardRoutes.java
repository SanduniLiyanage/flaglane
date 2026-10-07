package io.github.sanduniliyanage.flaglane.common.web;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The dashboard's own routes, answered with its page (ADR-031). The dashboard routes in the
 * browser, so a reload of {@code /projects/storefront/production/flags} reaches the server, which
 * answers with {@code index.html} and leaves the page to show what the address names. The image
 * serves the built dashboard as static resources; where there is none, as under {@code ./gradlew
 * bootRun}, the forward finds nothing and the answer is 404.
 *
 * <p>Only these paths are forwarded, and {@code SecurityConfiguration} opens only these and the
 * page's assets: everything else outside the two APIs stays closed.
 */
@Controller
@Hidden
public class DashboardRoutes {

  @GetMapping({"/sign-in", "/projects", "/projects/**"})
  String page() {
    return "forward:/index.html";
  }
}
