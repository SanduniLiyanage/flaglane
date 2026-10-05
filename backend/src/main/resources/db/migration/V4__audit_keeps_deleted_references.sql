-- FR-ENV-003 against FR-AUD-002 (ADR-022). Deleting an environment must leave its audit trail
-- intact, and audit_entries must never change. With these two foreign keys both cannot hold:
-- ON DELETE SET NULL is an UPDATE of audit_entries, which the append-only trigger from V3 refuses,
-- so no environment that had ever been audited could be deleted at all.
--
-- The entry keeps the id of the environment or flag it describes instead. A dangling id is the
-- accurate record of something that existed and was removed; a NULL would say less.

alter table audit_entries drop constraint fk_audit_entries_environment;
alter table audit_entries drop constraint fk_audit_entries_flag;
