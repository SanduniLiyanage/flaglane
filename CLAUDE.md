# Working agreement

This file is the constitution for this repository. Read it before every task. Where it conflicts
with a suggestion, this file wins. Where a document in `docs/` conflicts with this file, ask
before proceeding.

## Reading order at the start of a session

1. `CLAUDE.md` (this file)
2. `docs/ROADMAP.md` — what is done and what is next
3. `docs/SRS.md` — requirement IDs
4. `docs/ARCHITECTURE.md` — layering and algorithms
5. `docs/DECISIONS.md` — why things are the way they are

## Stack

| Concern | Choice |
| --- | --- |
| Language | Java 21 |
| Framework | Spring Boot 3.x |
| Build | Gradle (Kotlin DSL) |
| Database | PostgreSQL 16 |
| Migrations | Flyway |
| API docs | springdoc-openapi |
| Auth | Spring Security — JWT for dashboard, API key for SDK |
| Dashboard | React 18 + TypeScript + Vite |
| SDK | TypeScript, published to npm |
| Tests | JUnit 5, Mockito, Testcontainers, AssertJ |
| CI | GitHub Actions |

Do not add a dependency without asking. Every dependency is a maintenance obligation.

## Module layout

```
backend/src/main/java/io/github/sanduniliyanage/flaglane/
  common/            cross-cutting: errors, security, config, clock
  project/           projects and environments
  apikey/            key issuance, hashing, authentication
  flag/              flag definitions and per-environment configuration
  targeting/         rules, user overrides
  evaluation/        the engine — pure logic, no Spring, no database
  streaming/         SSE connections and change broadcast
  audit/             append-only change log
```

Each feature package contains `web/` (controllers, DTOs, mappers), `service/`, `domain/`,
and `persistence/` (entities, repositories).

`evaluation/` is special: it is a plain Java package with **no Spring annotations, no JPA
entities, and no database access**. It takes a ruleset and a user context and returns a value.
This is what makes it fast, portable, and trivially testable. Do not put persistence in it.

## Layer rules

- Controllers validate input and delegate. No business logic, no repository calls.
- Services own transactions and business rules.
- Entities never cross the API boundary. Every endpoint uses DTOs with explicit mappers.
- Repositories return entities or projections. No DTOs, no HTTP concerns.
- `evaluation/` imports nothing from the other packages.

## Build order for any new feature

Do not start a layer before the one beneath it has a passing test.

1. Domain types and invariants
2. Evaluation logic, if the feature touches it, with unit tests
3. Flyway migration
4. JPA entities and repositories, with Testcontainers tests
5. Service, with unit tests
6. DTOs, mappers, controller, with slice tests
7. OpenAPI annotations
8. Dashboard UI

## Non-negotiables

- **The evaluation path never throws to the caller.** A malformed rule, a missing flag, or an
  internal error resolves to the flag's default value and logs a warning. Callers depend on this.
- **Flaglane being down must not break the applications using it.** Design every SDK behaviour
  around this.
- **No N+1 queries on the evaluation path.** It serves from an in-memory snapshot, rebuilt on
  write, never assembled per request.
- **Tenant isolation is structural.** Every query that touches tenant data is scoped by the
  authenticated principal at the repository layer. It must not be possible to write a service
  method that forgets a `WHERE project_id = ?`.
- **API keys are hashed at rest** (SHA-256). The plaintext key is shown once at creation and
  never retrievable.
- **Every mutation writes an audit entry in the same transaction** as the change.
- **No secrets in source.** Configuration comes from environment variables. `.env` is gitignored.
- **Money and percentages are integers.** Rollout percentage is `int` 0–100, never a float.
- **Time comes from an injected `Clock`**, never `Instant.now()` inline, so tests can control it.

## Testing

- Repository and integration tests run against real PostgreSQL via Testcontainers. Never H2.
- `evaluation/` requires 90% line coverage; CI fails below it.
- Every bug fix starts with a failing test that reproduces it.
- See `docs/TESTING.md` for the required test suites.

## Commands

```bash
./gradlew spotlessApply        # format
./gradlew check                # format check + static analysis + tests
./gradlew test                 # tests only
./gradlew bootRun              # run the API
docker compose up              # full stack
npm --prefix dashboard run dev # dashboard
npm --prefix sdk test          # SDK tests
```

Run `./gradlew check` before claiming any slice is complete. If it fails, the slice is not done.

## Git and pull requests

- Conventional Commits. One logical change per commit.
- The commit body explains **why**, not what. The diff already shows what.
- Reference requirements: `Refs: FR-EVL-003`.
- Short-lived branches: `feat/evaluation-engine`, `fix/rule-precedence`.
- One PR per vertical slice. Squash merge. Wait for CI before merging.
- After opening a PR, give me the exact commands to run and stop.

## Repository voice

This repository is a product. Write every file — README, docs, comments, commit messages, issues
— for a developer evaluating whether to use Flaglane.

- No mention of CVs, internships, university, coursework, portfolios, or learning exercises.
- No AI tool attribution anywhere: no `Co-authored-by` trailers, no "generated with" footers, no
  references to the tools used to write the code, in commits, PRs, docs or source.
- Document limitations honestly. A stated limitation builds more trust than a hidden one.

## When you are unsure

Ask. A wrong assumption implemented across twelve files costs far more than one question.
