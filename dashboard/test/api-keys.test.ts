import { describe, expect, it } from "vitest";
import type { ApiKeyResponse } from "../src/api/schema";
import { KEY_TYPES, ordered, revokeWarning } from "../src/keys/apiKeys";

const key = (change: Partial<ApiKeyResponse>): ApiKeyResponse => ({
  id: "k",
  name: "checkout-service",
  type: "server",
  prefix: "flg_srv_Xk3mP9qa",
  createdAt: "2026-10-07T09:00:00Z",
  lastUsedAt: null,
  revokedAt: null,
  ...change,
});

describe("the key page", () => {
  it("lists live keys first, newest first", () => {
    const keys = [
      key({ id: "old-live", createdAt: "2026-10-01T00:00:00Z" }),
      key({ id: "revoked", createdAt: "2026-10-06T00:00:00Z", revokedAt: "2026-10-06T01:00:00Z" }),
      key({ id: "new-live", createdAt: "2026-10-05T00:00:00Z" }),
    ];

    expect(ordered(keys).map((k) => k.id)).toEqual(["new-live", "old-live", "revoked"]);
  });

  it("says what revoking does to applications already running and ones started later", () => {
    const warning = revokeWarning("checkout-service", "production");

    expect(warning).toContain("from the moment this returns");
    expect(warning).toContain("keep evaluating the production ruleset they last downloaded");
    expect(warning).toContain("defaults in its code");
    expect(warning).toContain("cannot be restored");
  });

  it("says a client key is public and carries no overrides", () => {
    expect(KEY_TYPES.client.reads).toMatch(/no user overrides/);
    expect(KEY_TYPES.client.reads).toMatch(/public/);
    expect(KEY_TYPES.server.reads).toMatch(/keep it secret/);
  });
});
