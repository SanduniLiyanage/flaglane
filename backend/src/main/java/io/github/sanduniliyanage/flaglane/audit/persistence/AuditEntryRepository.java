package io.github.sanduniliyanage.flaglane.audit.persistence;

import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Append only. There is no method that changes or removes an entry, here or in the database. */
public interface AuditEntryRepository extends Repository<AuditEntryEntity, UUID> {

  AuditEntryEntity save(AuditEntryEntity entry);
}
