-- Privileges for the application role. The role itself is provisioned by the environment before
-- Flyway runs (ADR-016): docker/postgres/init-roles.sh for Compose and the test fixture, the
-- provider's tooling for a managed database. This migration grants; it never creates a role and
-- never carries a credential. On a database where the role does not exist, the first GRANT fails
-- with the role named, which is the right place to fail.
--
-- flaglane_migrator owns every object and needs no grant. Tables added by later migrations get
-- no privileges by default; each migration grants what it adds.

grant select, insert, update, delete on
    users,
    projects,
    environments,
    api_keys,
    flags,
    flag_configs,
    targeting_rules,
    user_overrides
to flaglane_app;

-- FR-AUD-002: the application appends to the audit log and reads it back, nothing else. A fresh
-- table carries no privileges for the role, so the revoke changes nothing today; it is here so
-- the intent survives anyone later granting "all privileges on all tables" in one line.
grant select, insert on audit_entries to flaglane_app;
revoke update, delete, truncate, references, trigger on audit_entries from flaglane_app;
