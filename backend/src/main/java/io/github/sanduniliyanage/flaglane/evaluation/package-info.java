/**
 * The evaluation engine: a pure function from a {@link
 * io.github.sanduniliyanage.flaglane.evaluation.Ruleset}, a flag key and a {@link
 * io.github.sanduniliyanage.flaglane.evaluation.UserContext} to a value.
 *
 * <p>Plain Java by design. No Spring, no JPA, no database, and no imports from any other Flaglane
 * package, which {@code EvaluationPurityTest} enforces. That purity is what makes the engine
 * exhaustively testable without a container and precise enough for the TypeScript SDK to
 * reimplement identically (NFR-MNT-001).
 */
package io.github.sanduniliyanage.flaglane.evaluation;
