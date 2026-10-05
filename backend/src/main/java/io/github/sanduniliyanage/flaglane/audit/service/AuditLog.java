package io.github.sanduniliyanage.flaglane.audit.service;

import io.github.sanduniliyanage.flaglane.audit.domain.AuditEvent;
import io.github.sanduniliyanage.flaglane.audit.persistence.AuditEntryEntity;
import io.github.sanduniliyanage.flaglane.audit.persistence.AuditEntryRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the audit trail. Every mutation records an entry in the same transaction as the change
 * (FR-AUD-001), and this class makes that structural rather than a convention: {@link
 * Propagation#MANDATORY} refuses to record outside a transaction, so an entry can never be
 * committed on its own, nor a change committed while its entry fails.
 */
@Service
public class AuditLog {

  private final AuditEntryRepository entries;
  private final Clock clock;

  public AuditLog(AuditEntryRepository entries, Clock clock) {
    this.entries = entries;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(AuditEvent event) {
    entries.save(
        new AuditEntryEntity(
            UUID.randomUUID(),
            event.projectId(),
            event.environmentId(),
            event.flagId(),
            event.actorId(),
            event.action().value(),
            event.previousValue(),
            event.newValue(),
            clock.instant().truncatedTo(ChronoUnit.MICROS)));
  }
}
