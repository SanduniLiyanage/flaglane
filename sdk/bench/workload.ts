// The workload every benchmark here shares, and the server's EvaluationBenchmark transcribes: 1,000
// flags rolled out to 30%, three rules on each, and 50 on the flag under evaluation, none of which
// any benchmark user matches. Method and results: docs/BENCHMARKS.md.

export const FLAGS = 1_000;
export const RULES = 50;
export const FLAG = "flag-0500";

export interface ServedRule {
  priority: number;
  attribute: string;
  operator: string;
  matchValues: unknown[];
  resultValue: boolean;
}

export function flagKey(f: number): string {
  return `flag-${String(f).padStart(4, "0")}`;
}

/** How many rules flag `key` carries: `rules` on the flag under evaluation, three on the rest. */
export function ruleCount(key: string, rules: number): number {
  return key === FLAG ? rules : 3;
}

/**
 * Rule `r` of a list no benchmark user matches, cycling through every operator and every attribute
 * type, with the list operators holding ten values. EvaluationBenchmark.nonMatchingRule, transcribed.
 */
export function nonMatchingRule(r: number): ServedRule {
  const tenOf = (prefix: string) => Array.from({ length: 10 }, (_, i) => `${prefix}${r}-${i}`);
  let values = [
    [`ZZ${r}`],
    [`tier-${r}`],
    tenOf("Q"),
    tenOf("enterprise-"),
    [`@nowhere-${r}.invalid`],
    [`internal-${r}`],
    [1000 + r],
  ][r % 7] as unknown[];
  let attribute = ["country", "tier", "country", "plan", "email", "plan", "age"][r % 7] as string;
  let operator = ["EQUALS", "NOT_EQUALS", "IN", "IN", "CONTAINS", "STARTS_WITH", "EQUALS"][r % 7] as string;
  if (r % 7 === 3 && Math.floor(r / 7) % 2 !== 0) {
    operator = "NOT_IN";
    attribute = "tier";
  }
  if (r % 7 === 5 && Math.floor(r / 7) % 2 !== 0) {
    operator = "ENDS_WITH";
    attribute = "email";
    values = [`.invalid-${r}`];
  }
  return { priority: r, attribute, operator, matchValues: values, resultValue: true };
}

/** Nearest rank over sorted samples, as the server's harness computes it. */
export function percentile(sorted: ArrayLike<number>, fraction: number): number {
  return sorted[Math.max(0, Math.ceil(fraction * sorted.length) - 1)] as number;
}

/** One line of p50, p95, p99 and max, in milliseconds, over samples taken in milliseconds. */
export function reportMillis(name: string, samples: number[]): void {
  const sorted = Float64Array.from(samples).sort();
  const ms = (value: number) => value.toFixed(2).padStart(8);
  console.log(
    `${name.padEnd(44)} n ${String(sorted.length).padStart(5)}  p50 ${ms(percentile(sorted, 0.5))} ms` +
      `  p95 ${ms(percentile(sorted, 0.95))} ms  p99 ${ms(percentile(sorted, 0.99))} ms` +
      `  max ${ms(sorted[sorted.length - 1] as number)} ms`,
  );
}
