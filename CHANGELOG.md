# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Design documentation: requirements, architecture, database design, API contract, testing
  strategy, roadmap, decision log and workflow.
- Repository scaffolding: licence, contribution guide, security policy.
- Gradle multi-project build: Java 25, Spring Boot 3.5, Spotless, SpotBugs.
- Flyway `V1__baseline.sql` with the full schema, verified against PostgreSQL 16 by
  Testcontainers.
- Docker Compose stack: PostgreSQL 16 and the API built from source, with all credentials taken
  from `.env`.
- Two database roles: `flaglane_migrator` owns the schema and runs Flyway at startup;
  `flaglane_app` runs the application with no DDL privileges. Both are provisioned by the
  environment, never by a migration.
- Health endpoints: `/actuator/health` reports the database as a component;
  `/actuator/health/liveness` and `/actuator/health/readiness` never depend on it.

### Changed

- Corrections from a specification review of the design documents, recorded as errata E-001 to
  E-036 at the bottom of `docs/SRS.md`. The substantive ones: the kill switch is unconditional,
  bucketing is specified precisely enough for two implementations to agree, client rulesets no
  longer carry user identifiers, and the schedule is six weeks rather than four.

Nothing is released yet. The first release will be v0.1.0.
