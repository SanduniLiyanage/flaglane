import { describe, expect, it } from "vitest";
import type { AuditEntryResponse } from "../src/api/schema";
import { actionLabel, actorName, basisPoints, changesOf, shown } from "../src/audit/present";

const entry = (change: Partial<AuditEntryResponse>): AuditEntryResponse => ({
  id: "e",
  createdAt: "2026-10-07T09:30:00Z",
  action: "config.updated",
  actor: { email: "amara@example.com", displayName: "Amara" },
  environment: "production",
  environmentDeleted: false,
  flag: "new-checkout",
  previousValue: null,
  newValue: null,
  ...change,
});

describe("an audit entry", () => {
  it("shows a rollout in the basis points it was recorded in, with the percentage only when whole", () => {
    expect(basisPoints(3000)).toBe("3000 basis points (30%)");
    expect(basisPoints(0)).toBe("0 basis points (0%)");
    expect(basisPoints(3050)).toBe("3050 basis points");
  });

  it("lists only the fields an edit changed, as recorded", () => {
    const changes = changesOf(
      entry({
        previousValue: { enabled: true, fallthroughValue: false, rolloutBasisPoints: 1000, rolloutSalt: "new-checkout" },
        newValue: { enabled: true, fallthroughValue: false, rolloutBasisPoints: 3000, rolloutSalt: "new-checkout" },
      }),
    );

    expect(changes).toEqual([
      { field: "rolloutBasisPoints", before: "1000 basis points (10%)", after: "3000 basis points (30%)" },
    ]);
  });

  it("lists every field of a creation, with nothing before", () => {
    const changes = changesOf(
      entry({ action: "key.created", flag: null, newValue: { name: "checkout-service", type: "server", revokedAt: null } }),
    );

    expect(changes).toEqual([
      { field: "name", before: null, after: "checkout-service" },
      { field: "type", before: null, after: "server" },
      { field: "revokedAt", before: null, after: "none" },
    ]);
  });

  it("writes recorded rules and overrides out, operator names as recorded", () => {
    expect(
      shown("rules", [{ attribute: "country", operator: "IN", matchValues: ["LK", "IN"], resultValue: true }]),
    ).toBe('country IN ["LK","IN"] → true');
    expect(shown("overrides", [{ userKey: "u-1042", value: true }])).toBe("u-1042 → true");
    expect(shown("rules", [])).toBe("none");
  });

  it("names the actor and the action in words, keeping an action it does not know as recorded", () => {
    expect(actorName(entry({}))).toBe("Amara (amara@example.com)");
    expect(actorName(entry({ actor: { email: "a@example.com", displayName: null } }))).toBe("a@example.com");
    expect(actionLabel("rules.replaced")).toBe("replaced a flag's rules");
    expect(actionLabel("something.new")).toBe("something.new");
  });
});
