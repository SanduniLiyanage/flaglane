import { describe, expect, it } from "vitest";
import type { FlagConfigResponse } from "../src/api/schema";
import { changes, isDirty, percentageFrom, rolloutIsInert, stagedFrom } from "../src/flags/staging";

const saved: FlagConfigResponse = {
  flagKey: "new-checkout",
  environment: "production",
  enabled: true,
  offValue: false,
  fallthroughValue: false,
  rolloutPercentage: 30,
  rolloutSalt: "new-checkout",
  updatedAt: "2026-10-07T09:30:00Z",
};

describe("staged edits", () => {
  it("start from the saved configuration with nothing to save", () => {
    const staged = stagedFrom(saved);

    expect(staged).toEqual({ fallthroughValue: false, rolloutPercentage: 30 });
    expect(isDirty(saved, staged)).toBe(false);
    expect(changes(saved, staged)).toEqual({});
  });

  it("save only the fields that changed", () => {
    expect(changes(saved, { fallthroughValue: false, rolloutPercentage: 45 })).toEqual({ rolloutPercentage: 45 });
    expect(changes(saved, { fallthroughValue: true, rolloutPercentage: 30 })).toEqual({ fallthroughValue: true });
  });

  it("never send the kill switch, which saves on its own", () => {
    const body = changes(saved, { fallthroughValue: true, rolloutPercentage: 0 });

    expect(body).not.toHaveProperty("enabled");
    expect(body).toEqual({ fallthroughValue: true, rolloutPercentage: 0 });
  });

  it("are clean again once moved back to what is saved", () => {
    expect(isDirty(saved, { fallthroughValue: false, rolloutPercentage: 30 })).toBe(false);
  });

  it("make the rollout inert while the fallthrough value is true, and keep its percentage", () => {
    const staged = { fallthroughValue: true, rolloutPercentage: 30 };

    expect(rolloutIsInert(staged)).toBe(true);
    expect(changes(saved, staged)).toEqual({ fallthroughValue: true });
    expect(rolloutIsInert({ fallthroughValue: false, rolloutPercentage: 30 })).toBe(false);
  });
});

describe("a rollout percentage from the slider", () => {
  it.each(["0", "1", "30", "100"])("is the whole number %s", (value) => {
    expect(percentageFrom(value)).toBe(Number(value));
  });

  it.each(["12.5", "-1", "101", "", "abc", "1e400"])("refuses %j rather than rounding it", (value) => {
    expect(() => percentageFrom(value)).toThrow(RangeError);
  });
});
