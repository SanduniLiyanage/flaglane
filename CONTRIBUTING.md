# Contributing

Thanks for your interest in Flaglane. Bug reports, reproductions and focused pull requests are all
welcome.

## Before you start

Open an issue before writing a large change. The design is documented, and a patch that conflicts
with it costs both of us time. For small fixes, go straight to a pull request.

Read [`docs/ROADMAP.md`](docs/ROADMAP.md) to see what is in v0.1 and what is deliberately out, and
[`docs/DECISIONS.md`](docs/DECISIONS.md) for why the design is the way it is.

## Development setup

```bash
git clone https://github.com/SanduniLiyanage/flaglane.git
cd flaglane
docker compose up
```

The API is on `http://localhost:8080`, Swagger UI on `http://localhost:8080/swagger-ui.html`, and
the dashboard on `http://localhost:5173`.

## Before opening a pull request

```bash
./gradlew spotlessApply
./gradlew check
npm --prefix sdk test
npm --prefix dashboard run build
```

If `check` fails, the change is not ready. It runs formatting, static analysis, the test suites and
the coverage gate on `evaluation/`.

## What a pull request needs

One logical change per pull request. The description states:

1. Which requirement IDs from [`docs/SRS.md`](docs/SRS.md) it touches, if any
2. What changed and why that approach
3. What was tested, naming any suite from [`docs/TESTING.md`](docs/TESTING.md) that now passes
4. Anything deliberately left out

Commits follow [Conventional Commits](https://www.conventionalcommits.org/). The subject says what
changed; the body says why, because the diff already shows what.

## Testing expectations

- Every bug fix opens with a failing test that reproduces the bug.
- Repository and integration tests run against real PostgreSQL via Testcontainers, never H2.
- `evaluation/` is pure Java with no framework dependency and must stay above 90% line coverage.
- The server and the TypeScript SDK are checked against the same fixture set. If you change
  evaluation semantics, both sides and the fixtures change together.

Full detail is in [`docs/TESTING.md`](docs/TESTING.md) and [`docs/WORKFLOW.md`](docs/WORKFLOW.md).

## Dependencies

Adding a dependency is a maintenance obligation for everyone who self-hosts this. Propose it in an
issue first, with what it replaces and why it is worth the weight.

## Security issues

Do not open a public issue for a vulnerability. See [`SECURITY.md`](SECURITY.md).

## Licence

By contributing, you agree that your contributions are licensed under the Apache License 2.0, as
stated in [`LICENSE`](LICENSE).
