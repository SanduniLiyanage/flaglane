#!/bin/sh
# Provisions the two database roles Flaglane runs with. The official postgres image executes
# this once, as the bootstrap superuser, when the data directory is first initialised. It is
# shared by docker-compose.yml and the Testcontainers fixture so the two cannot drift.
#
# Roles and their passwords are an environment concern; migrations grant privileges but never
# carry credentials (ADR-016). Nothing in this file is a secret.
set -eu

: "${FLAGLANE_DB_MIGRATOR_PASSWORD:?FLAGLANE_DB_MIGRATOR_PASSWORD must be set}"
: "${FLAGLANE_DB_APP_PASSWORD:?FLAGLANE_DB_APP_PASSWORD must be set}"

psql -v ON_ERROR_STOP=1 \
     -v migrator_password="$FLAGLANE_DB_MIGRATOR_PASSWORD" \
     -v app_password="$FLAGLANE_DB_APP_PASSWORD" \
     -v database="$POSTGRES_DB" \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
-- Owns the schema and runs Flyway at startup. Owning the database makes it the owner of the
-- public schema through pg_database_owner, which is all the DDL privilege it needs.
create role flaglane_migrator login password :'migrator_password';
alter database :"database" owner to flaglane_migrator;

-- Runs the application. Table privileges are granted by migration (V2__roles.sql); this script
-- only lets it connect and resolve names in public.
create role flaglane_app login password :'app_password';
grant connect on database :"database" to flaglane_app;
grant usage on schema public to flaglane_app;
SQL
