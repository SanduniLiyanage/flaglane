-- FR-AUD-003. Reading the audit trail names each entry's environment by key. A deleted environment
-- has no row to take the key from, so the reader takes it from the environment's deletion entry,
-- which recorded it (ADR-022). This index finds that entry directly instead of scanning the
-- project's whole trail once for every entry on a page. Deletions are rare, so the index is small.

create index ix_audit_entries_environment_deleted
    on audit_entries (environment_id)
    where action = 'environment.deleted';
