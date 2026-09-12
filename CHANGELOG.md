# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Design documentation: requirements, architecture, database design, API contract, testing
  strategy, roadmap, decision log and workflow.
- Repository scaffolding: licence, contribution guide, security policy.

### Changed

- Corrections from a specification review of the design documents, recorded as errata E-001 to
  E-036 at the bottom of `docs/SRS.md`. The substantive ones: the kill switch is unconditional,
  bucketing is specified precisely enough for two implementations to agree, client rulesets no
  longer carry user identifiers, and the schedule is six weeks rather than four.

Nothing is released yet. The first release will be v0.1.0.
