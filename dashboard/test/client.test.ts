import { describe, expect, it } from "vitest";
import { ApiError, client, resolve } from "../src/api/client";
import { SessionStore } from "../src/session/session";

interface Sent {
  url: string;
  init: RequestInit;
}

function recording(response: () => Response) {
  const sent: Sent[] = [];
  const send = (async (url: string, init: RequestInit) => {
    sent.push({ url, init });
    return response();
  }) as unknown as typeof fetch;
  return { sent, send };
}

function signedIn(): SessionStore {
  const store = new SessionStore();
  store.start("eyJ.token", "amara@example.com", new Date(Date.now() + 3_600_000).toISOString());
  return store;
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

describe("the API client", () => {
  it("fills the path, sends the token and the body, and returns the answer", async () => {
    const store = signedIn();
    const { sent, send } = recording(() => json(200, { flagKey: "new-checkout", rolloutPercentage: 30 }));
    const api = client(store, send);

    const answer = await api(
      "PATCH /api/projects/{projectKey}/flags/{flagKey}/config/{envKey}",
      { projectKey: "storefront", flagKey: "new-checkout", envKey: "production" },
      { rolloutPercentage: 30 },
    );

    expect(answer).toMatchObject({ rolloutPercentage: 30 });
    expect(sent).toHaveLength(1);
    expect(sent[0]?.url).toBe("/api/projects/storefront/flags/new-checkout/config/production");
    expect(sent[0]?.init.method).toBe("PATCH");
    expect(sent[0]?.init.body).toBe('{"rolloutPercentage":30}');
    expect(sent[0]?.init.headers).toMatchObject({
      authorization: "Bearer eyJ.token",
      "content-type": "application/json",
    });
    store.end("signed-out");
  });

  it("sends no token when there is no session", async () => {
    const { sent, send } = recording(() => json(200, { accessToken: "eyJ", tokenType: "Bearer", expiresAt: "x" }));

    await client(new SessionStore(), send)("POST /api/auth/login", {}, { email: "a@example.com", password: "p" });

    expect(sent[0]?.init.headers).not.toHaveProperty("authorization");
  });

  it("ends the session when the API refuses its token", async () => {
    const store = signedIn();
    const { send } = recording(() => json(401, { status: 401, title: "Unauthorized" }));

    await expect(client(store, send)("GET /api/projects", {})).rejects.toMatchObject({ status: 401 });

    expect(store.state.session).toBeNull();
    expect(store.state.ended?.reason).toBe("rejected");
  });

  it("turns a problem response into an error naming its fields", async () => {
    const store = signedIn();
    const { send } = recording(() =>
      json(400, {
        status: 400,
        detail: "The request has invalid fields",
        errors: [{ field: "key", message: "must be 1 to 63 lowercase letters" }],
      }),
    );

    const error = await client(store, send)("POST /api/projects", {}, { key: "Bad Key", name: "x" }).catch((e) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error.message).toBe("The request has invalid fields");
    expect(error.fields.get("key")).toBe("must be 1 to 63 lowercase letters");
    expect(store.state.session).not.toBeNull();
    store.end("signed-out");
  });

  it("says so when Flaglane cannot be reached", async () => {
    const send = (async () => {
      throw new TypeError("fetch failed");
    }) as unknown as typeof fetch;

    await expect(client(new SessionStore(), send)("GET /api/projects", {})).rejects.toMatchObject({
      status: 0,
      message: expect.stringContaining("could not be reached"),
    });
  });

  it("returns nothing for a 204", async () => {
    const store = signedIn();
    const { send } = recording(() => new Response(null, { status: 204 }));

    await expect(
      client(store, send)("DELETE /api/projects/{projectKey}/environments/{envKey}", {
        projectKey: "storefront",
        envKey: "qa",
      }),
    ).resolves.toBeUndefined();
    store.end("signed-out");
  });
});

describe("resolving a path", () => {
  it("encodes path variables and puts the other parameters in the query", () => {
    expect(resolve("/api/projects/{projectKey}/audit", { projectKey: "a b", flag: "x", limit: 10, before: undefined })).toBe(
      "/api/projects/a%20b/audit?flag=x&limit=10",
    );
  });

  it("refuses to call a path with a variable missing", () => {
    expect(() => resolve("/api/projects/{projectKey}", {})).toThrow(/needs projectKey/);
  });
});
