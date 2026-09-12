# Workflow

## Branches

`main` is always releasable. Work happens on short-lived branches, one per slice from
`docs/ROADMAP.md`.

```
feat/evaluation-bucketing
fix/rule-priority-reorder
docs/api-contract
chore/ci-coverage-gate
```

## Commits

Conventional Commits. One logical change per commit. The subject says what changed; the body says
why, because the diff already shows what.

```
feat(evaluation): salt bucket hash with flag key

Without the flag key in the hash input, every user carries one fixed
bucket across all flags, so the same 30% of users would be the test
population for every rollout in the system. Correlated failures would
then look like one broken cohort rather than one broken feature.

Refs: FR-EVL-004
```

Scopes match module names: `evaluation`, `flag`, `apikey`, `project`, `targeting`, `streaming`,
`audit`, `sdk`, `dashboard`, `ci`, `docs`.

No tool attribution in commits or pull requests: no `Co-authored-by` trailers for AI tools, no
"generated with" footers, no references to how the code was written.

## Pull requests

One slice per pull request. The description states:

1. Which roadmap slice and which requirement IDs
2. What changed and why the approach was chosen
3. What was tested, naming any suite from `docs/TESTING.md` that now passes
4. Anything deliberately left out

Squash merge after CI is green. A red build is never merged.

## Before opening a pull request

```bash
./gradlew spotlessApply
./gradlew check
npm --prefix sdk test
npm --prefix dashboard run build
```

If `check` fails, the slice is not finished.

## Definition of done

A slice is done when all of these hold:

- Requirements in `docs/SRS.md` are satisfied and referenced in the commit
- Tests exist at the right layer, and any relevant suite from `docs/TESTING.md` passes
- `./gradlew check` is green, including the coverage gate
- OpenAPI reflects any new or changed endpoint
- `docs/ROADMAP.md` status is updated
- `docs/` reflects what was built: any document that describes something differently from how it
  was implemented is updated in the same PR, with the change recorded in `docs/SRS.md` errata if it
  touches a requirement
- A decision with lasting consequences is recorded in `docs/DECISIONS.md`
- No `TODO` remains without a linked issue

## Changing a requirement

Requirements change; pretending otherwise produces documents nobody trusts. When one does:

1. Add a row to the errata table at the bottom of `docs/SRS.md`
2. State what changed and the evidence that forced it
3. Reference the erratum ID in the commit

Do not silently edit a requirement to match the code. The record of why the design moved is worth
more than the appearance of having got it right first time.
