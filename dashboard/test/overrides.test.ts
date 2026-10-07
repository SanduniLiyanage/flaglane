import { describe, expect, it } from "vitest";
import { draftsFrom, newOverride, overridesChanged, problemsOf, toRequests } from "../src/flags/overrides";

describe("a staged override set", () => {
  const saved = [
    { userKey: "u-1042", value: true },
    { userKey: "u-7", value: false },
  ];

  it("round-trips the stored overrides", () => {
    const drafts = draftsFrom(saved);

    expect(toRequests(drafts)).toEqual(saved);
    expect(overridesChanged(saved, drafts)).toBe(false);
  });

  it("is a set: the same entries in another order are no change", () => {
    expect(overridesChanged(saved, draftsFrom([...saved].reverse()))).toBe(false);
  });

  it("counts a changed value, a removal or an addition as a change", () => {
    const drafts = draftsFrom(saved);

    expect(overridesChanged(saved, [{ ...drafts[0]!, value: false }, drafts[1]!])).toBe(true);
    expect(overridesChanged(saved, drafts.slice(1))).toBe(true);
    expect(overridesChanged(saved, [...drafts, { ...newOverride(), userKey: "u-9" }])).toBe(true);
  });

  it("holds each user key to one entry, marking the later one", () => {
    const drafts = draftsFrom([...saved, { userKey: "u-1042", value: false }]);

    const problems = problemsOf(drafts);

    expect(problems.size).toBe(1);
    expect(problems.get(drafts[2]!.id)).toMatch(/appears once/);
  });

  it("needs a user key of at most 256 characters", () => {
    const drafts = [
      { ...newOverride(), userKey: "  " },
      { ...newOverride(), userKey: "x".repeat(257) },
      { ...newOverride(), userKey: "x".repeat(256) },
    ];

    const problems = problemsOf(drafts);

    expect(problems.get(drafts[0]!.id)).toMatch(/Enter the user key/);
    expect(problems.get(drafts[1]!.id)).toMatch(/at most 256/);
    expect(problems.has(drafts[2]!.id)).toBe(false);
  });
});
