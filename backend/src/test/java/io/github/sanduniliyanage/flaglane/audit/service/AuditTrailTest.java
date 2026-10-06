package io.github.sanduniliyanage.flaglane.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditCursor;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditEntry;
import io.github.sanduniliyanage.flaglane.audit.domain.AuditPage;
import io.github.sanduniliyanage.flaglane.audit.persistence.AuditTrailReader;
import io.github.sanduniliyanage.flaglane.audit.persistence.AuditTrailRow;
import io.github.sanduniliyanage.flaglane.common.errors.NotFoundException;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantResolver;
import io.github.sanduniliyanage.flaglane.common.tenancy.TenantScopes;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AuditTrailTest {

  private static final Instant T0 = Instant.parse("2026-10-06T09:00:00.000001Z");

  private final AuditTrailReader reader = mock(AuditTrailReader.class);
  private final TenantResolver tenants = mock(TenantResolver.class);
  private final AuditTrail trail = new AuditTrail(reader, tenants, new ObjectMapper());
  private final ProjectScope project =
      TenantScopes.project(UUID.randomUUID(), "storefront", UUID.randomUUID());

  @Test
  void aFullPageAsksForOneMoreRowAndPointsNextAtItsOwnLastEntry() {
    List<AuditTrailRow> rows = rows(4);
    when(reader.page(project, null, null, null, 4)).thenReturn(rows);

    AuditPage page = trail.page(project, null, null, null, 3);

    assertThat(page.entries()).extracting(AuditEntry::id).containsExactly(ids(rows, 3));
    assertThat(page.next()).contains(new AuditCursor(rows.get(2).createdAt(), rows.get(2).id()));
  }

  @Test
  void theLastPageHasNoNext() {
    when(reader.page(project, null, null, null, 4)).thenReturn(rows(3));

    AuditPage page = trail.page(project, null, null, null, 3);

    assertThat(page.entries()).hasSize(3);
    assertThat(page.next()).isEmpty();
  }

  @Test
  void anEnvironmentIsResolvedInTheProjectAndAFlagCheckedBeforeReading() {
    EnvironmentScope production =
        TenantScopes.environment(project, UUID.randomUUID(), "production");
    when(tenants.environment(project, "production")).thenReturn(production);
    AuditCursor before = new AuditCursor(T0, UUID.randomUUID());
    when(reader.page(project, production, "checkout", before, 51)).thenReturn(List.of());

    AuditPage page = trail.page(project, "production", "checkout", before, 50);

    verify(tenants).requireFlag(project, "checkout");
    assertThat(page.entries()).isEmpty();
  }

  @Test
  void anUnknownEnvironmentOrFlagIsNotFoundAndNothingIsRead() {
    when(tenants.environment(project, "qa"))
        .thenThrow(new NotFoundException("Environment not found"));
    doThrow(new NotFoundException("Flag not found")).when(tenants).requireFlag(project, "nope");

    assertThatExceptionOfType(NotFoundException.class)
        .isThrownBy(() -> trail.page(project, "qa", null, null, 10));
    assertThatExceptionOfType(NotFoundException.class)
        .isThrownBy(() -> trail.page(project, null, "nope", null, 10));
    verifyNoInteractions(reader);
  }

  @Test
  void recordedStatesArePassedOnAsTheyWereStored() {
    AuditTrailRow row =
        new AuditTrailRow(
            UUID.randomUUID(),
            T0,
            "flag.updated",
            "amara@example.com",
            null,
            null,
            false,
            "checkout",
            "{\"description\": null, \"rolloutBasisPoints\": 3000}",
            null);
    when(reader.page(eq(project), any(), any(), any(), anyInt())).thenReturn(List.of(row));

    AuditEntry entry = trail.page(project, null, null, null, 1).entries().getFirst();

    Map<String, Object> expected = new HashMap<>();
    expected.put("description", null);
    expected.put("rolloutBasisPoints", 3000);
    assertThat(entry.previousValue()).isEqualTo(expected);
    assertThat(entry.newValue()).isNull();
  }

  @Test
  void aLimitOutsideOneToAHundredIsRefused() {
    assertThatIllegalArgumentException().isThrownBy(() -> trail.page(project, null, null, null, 0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> trail.page(project, null, null, null, 101));
  }

  private static List<AuditTrailRow> rows(int count) {
    return IntStream.range(0, count)
        .mapToObj(
            i ->
                new AuditTrailRow(
                    UUID.randomUUID(),
                    T0.minusSeconds(i),
                    "flag.updated",
                    "amara@example.com",
                    "Amara",
                    null,
                    false,
                    "checkout",
                    "{}",
                    "{}"))
        .toList();
  }

  private static UUID[] ids(List<AuditTrailRow> rows, int count) {
    return rows.stream().limit(count).map(AuditTrailRow::id).toArray(UUID[]::new);
  }
}
