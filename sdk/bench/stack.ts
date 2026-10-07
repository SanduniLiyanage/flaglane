// A benchmark environment on a running Flaglane, built through the management API as a user would
// build it: a fresh account and project, the shared workload's 1,000 flags in production with
// their rules, and a server key. Nothing is reused between runs.

import { randomBytes } from "node:crypto";
import { FLAGS, flagKey, nonMatchingRule, RULES, ruleCount } from "./workload.ts";

export const baseUrl = (process.env.FLAGLANE_URL ?? "http://localhost:8080").replace(/\/+$/, "");
export const ENVIRONMENT = "production";

export class Api {
  readonly #token: string;

  private constructor(token: string) {
    this.#token = token;
  }

  static async signUp(): Promise<Api> {
    const email = `bench-${randomBytes(6).toString("hex")}@example.com`;
    const password = randomBytes(18).toString("base64url");
    await send("POST", "/api/auth/register", undefined, { email, password }, [201]);
    const session = (await send("POST", "/api/auth/login", undefined, { email, password }, [200])) as {
      accessToken: string;
    };
    return new Api(session.accessToken);
  }

  call(method: string, path: string, body?: unknown): Promise<unknown> {
    return send(method, path, this.#token, body, [200, 201, 204]);
  }
}

export interface BenchmarkEnvironment {
  readonly api: Api;
  readonly project: string;
  readonly sdkKey: string;
}

/** Builds the shared workload in a new project and returns a server key for its production. */
export async function benchmarkEnvironment(): Promise<BenchmarkEnvironment> {
  const api = await Api.signUp();
  const project = `bench-${randomBytes(4).toString("hex")}`;
  await api.call("POST", "/api/projects", { key: project, name: "Benchmark" });
  const started = performance.now();
  for (let f = 0; f < FLAGS; f++) {
    const key = flagKey(f);
    await api.call("POST", `/api/projects/${project}/flags`, { key, name: key });
    await api.call("PATCH", `/api/projects/${project}/flags/${key}/config/${ENVIRONMENT}`, {
      enabled: true,
      fallthroughValue: false,
      rolloutPercentage: 30,
    });
    const rules = Array.from({ length: ruleCount(key, RULES) }, (_, r) => {
      const { priority: _priority, ...rule } = nonMatchingRule(r);
      return rule;
    });
    await api.call("PUT", `/api/projects/${project}/flags/${key}/config/${ENVIRONMENT}/rules`, { rules });
  }
  const issued = (await api.call("POST", `/api/projects/${project}/environments/${ENVIRONMENT}/keys`, {
    name: "benchmark",
    type: "server",
  })) as { key: string };
  console.log(`Built ${FLAGS} flags in ${project} in ${((performance.now() - started) / 1000).toFixed(0)} s`);
  return { api, project, sdkKey: issued.key };
}

/** The ruleset version a key's environment is serving now. */
export async function servedVersion(sdkKey: string): Promise<number> {
  const response = await fetch(`${baseUrl}/sdk/config`, { headers: { authorization: `Bearer ${sdkKey}` } });
  if (response.status !== 200) {
    throw new Error(`GET /sdk/config answered ${response.status}`);
  }
  return ((await response.json()) as { version: number }).version;
}

async function send(
  method: string,
  path: string,
  token: string | undefined,
  body: unknown,
  expected: number[],
): Promise<unknown> {
  const headers: Record<string, string> = { accept: "application/json" };
  if (token !== undefined) {
    headers.authorization = `Bearer ${token}`;
  }
  if (body !== undefined) {
    headers["content-type"] = "application/json";
  }
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers,
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const text = await response.text();
  if (!expected.includes(response.status)) {
    throw new Error(`${method} ${path} answered ${response.status}: ${text}`);
  }
  return text === "" ? undefined : JSON.parse(text);
}
