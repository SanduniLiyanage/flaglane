import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

/**
 * The fixtures the server's test suite runs too, read from where the server keeps them. There is
 * one copy: the SDK never carries fixtures of its own, so the two implementations cannot be tested
 * against different expectations.
 */
const directory = new URL("../../backend/src/test/resources/fixtures/", import.meta.url);

export function fixtureText(name: string): string {
  return readFileSync(fileURLToPath(new URL(name, directory)), "utf8");
}

export function fixture<T>(name: string): T {
  return JSON.parse(fixtureText(name)) as T;
}

export interface ParityCase {
  name: string;
  ruleset: string;
  flag: string;
  context: { key?: string | null; attributes?: Record<string, unknown> };
  fallback: boolean;
  expected: { value: boolean; reason: string };
}

export interface ParityFixture {
  rulesets: Record<string, unknown>;
  cases: ParityCase[];
}

export interface ComparisonCase {
  name: string;
  attribute?: string | null;
  operator: string | null;
  matchValues: unknown[] | null;
  attributes: Record<string, unknown>;
  expected: "match" | "no-match" | "malformed";
}

export interface BucketingFixture {
  murmur3: { input: string; seed: number; hash: string }[];
  buckets: { salt: string; userKey: string; bucket: number }[];
  keySet: { file: string; salt: string; sha256: string };
}
