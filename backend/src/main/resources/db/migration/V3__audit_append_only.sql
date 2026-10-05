-- FR-AUD-002: audit_entries is append-only, and the revoke in V2 alone does not make it so. The
-- owning role can grant itself anything back, TRUNCATE is a separate privilege from DELETE, and a
-- superuser bypasses privilege checks entirely. A trigger holds regardless of role, ownership or
-- superuser status, which is what makes the guarantee a guarantee.
--
-- Two triggers, not one: a row-level BEFORE UPDATE OR DELETE trigger does not fire on TRUNCATE,
-- which needs a statement-level trigger of its own.

create function reject_audit_mutation() returns trigger as $$
begin
    raise exception 'audit_entries is append-only: % is not permitted', tg_op;
end;
$$ language plpgsql;

create trigger trg_audit_entries_append_only
    before update or delete on audit_entries
    for each row execute function reject_audit_mutation();

create trigger trg_audit_entries_no_truncate
    before truncate on audit_entries
    for each statement execute function reject_audit_mutation();
