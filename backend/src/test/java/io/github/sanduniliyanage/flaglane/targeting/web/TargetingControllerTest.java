package io.github.sanduniliyanage.flaglane.targeting.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sanduniliyanage.flaglane.ManagementApiSliceTest;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantResolver;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantScopes;
import io.github.sanduniliyanage.flaglane.targeting.domain.Rule;
import io.github.sanduniliyanage.flaglane.targeting.domain.UserOverride;
import io.github.sanduniliyanage.flaglane.targeting.service.TargetingService;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** FR-RUL-010: rules are validated when written, not when evaluated. */
@ManagementApiSliceTest(TargetingController.class)
class TargetingControllerTest {

  private static final UUID USER = UUID.fromString("0f6b3b5e-7c2a-4f5e-9d61-2a4b8f0e1c3d");
  private static final String CONFIG = "/api/projects/storefront/flags/checkout/config/production";

  private final MockMvc mvc;
  private final ProjectScope project = TenantScopes.project(UUID.randomUUID(), "storefront", USER);
  private final EnvironmentScope production =
      TenantScopes.environment(project, UUID.randomUUID(), "production");

  @MockitoBean private TargetingService targeting;
  @MockitoBean private TenantResolver tenants;

  TargetingControllerTest(@Autowired MockMvc mvc) {
    this.mvc = mvc;
  }

  @BeforeEach
  void resolvesTheEnvironment() {
    when(tenants.project(TenantScopes.owner(USER), "storefront")).thenReturn(project);
    when(tenants.environment(project, "production")).thenReturn(production);
  }

  @Test
  void rulesAreReplacedInTheOrderGiven() throws Exception {
    List<Rule> rules =
        List.of(
            new Rule("country", "IN", List.of("LK", "IN"), true),
            new Rule("plan", "EQUALS", List.of("free"), false));
    when(targeting.replaceRules(production, "checkout", rules)).thenReturn(rules);

    putRules(
            "{\"rules\":[{\"attribute\":\"country\",\"operator\":\"IN\",\"matchValues\":[\"LK\",\"IN\"],"
                + "\"resultValue\":true},{\"attribute\":\"plan\",\"operator\":\"EQUALS\","
                + "\"matchValues\":[\"free\"],\"resultValue\":false}]}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rules[1].attribute").value("plan"));
    verify(targeting).replaceRules(eq(production), eq("checkout"), eq(rules));
  }

  @Test
  void aRuleTheEngineCouldNotApplyIsRefusedNamingIt() throws Exception {
    putRules(
            "{\"rules\":[{\"attribute\":\"plan\",\"operator\":\"EQUALS\",\"matchValues\":[\"a\"],"
                + "\"resultValue\":true},{\"attribute\":\"plan\",\"operator\":\"MATCHES_REGEX\","
                + "\"matchValues\":[\"^a\"],\"resultValue\":true}]}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("rules[1]"))
        .andExpect(jsonPath("$.errors[0].message").value("unknown operator MATCHES_REGEX"));
    verifyNoInteractions(targeting);
  }

  @Test
  void anEmptyValueListIsRefused() throws Exception {
    putRules(
            "{\"rules\":[{\"attribute\":\"plan\",\"operator\":\"IN\",\"matchValues\":[],"
                + "\"resultValue\":true}]}")
        .andExpect(status().isBadRequest());
    verifyNoInteractions(targeting);
  }

  @Test
  void aStringOperatorWithANumberIsRefused() throws Exception {
    putRules(
            "{\"rules\":[{\"attribute\":\"plan\",\"operator\":\"CONTAINS\",\"matchValues\":[1],"
                + "\"resultValue\":true}]}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].message").value("CONTAINS compares strings only"));
  }

  @Test
  void anOperatorNameIsReportedAsTextNeverEvaluated() throws Exception {
    putRules(
            "{\"rules\":[{\"attribute\":\"plan\",\"operator\":\"${1+1}{x}\",\"matchValues\":[\"a\"],"
                + "\"resultValue\":true}]}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].message").value("unknown operator ${1+1}{x}"));
  }

  @Test
  void moreThanAHundredRulesAreRefused() throws Exception {
    String rule =
        "{\"attribute\":\"plan\",\"operator\":\"EQUALS\",\"matchValues\":[\"a\"],\"resultValue\":true}";
    String rules = IntStream.range(0, 101).mapToObj(i -> rule).collect(Collectors.joining(","));

    putRules("{\"rules\":[" + rules + "]}").andExpect(status().isBadRequest());
    verifyNoInteractions(targeting);
  }

  @Test
  void overridesAreReplacedAsASet() throws Exception {
    List<UserOverride> overrides = List.of(new UserOverride("u-1", true));
    when(targeting.replaceOverrides(production, "checkout", overrides)).thenReturn(overrides);

    putOverrides("{\"overrides\":[{\"userKey\":\"u-1\",\"value\":true}]}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.overrides[0].userKey").value("u-1"));
  }

  @Test
  void oneUserKeyTwiceIsRefused() throws Exception {
    putOverrides(
            "{\"overrides\":[{\"userKey\":\"u-1\",\"value\":true},{\"userKey\":\"u-1\",\"value\":false}]}")
        .andExpect(status().isBadRequest());
    verifyNoInteractions(targeting);
  }

  private ResultActions putRules(String body) throws Exception {
    return mvc.perform(
        put(CONFIG + "/rules")
            .with(jwt().jwt(token -> token.subject(USER.toString())))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions putOverrides(String body) throws Exception {
    return mvc.perform(
        put(CONFIG + "/overrides")
            .with(jwt().jwt(token -> token.subject(USER.toString())))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }
}
