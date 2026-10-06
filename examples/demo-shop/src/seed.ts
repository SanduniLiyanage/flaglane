// Sets the demo up through the management API, the way a dashboard user would: an account, a
// project, two flags configured in development, and a server key. Running it again puts the flags
// back to where the demo starts and keeps the account and key it wrote to .env.

import { randomBytes } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { ApiError, type Config, Management } from "./management.ts";
import { ENVIRONMENT, FREE_SHIPPING, NEW_CHECKOUT, flaglaneUrl, projectKey, sdkKey } from "./settings.ts";

const envFile = fileURLToPath(new URL("../.env", import.meta.url));

const email = process.env.FLAGLANE_DEMO_EMAIL || "demo-shop@example.com";
const generated = !process.env.FLAGLANE_DEMO_PASSWORD;
const password = process.env.FLAGLANE_DEMO_PASSWORD || randomBytes(18).toString("base64url");

const project = `/api/projects/${projectKey}`;
const config = (flag: string) => `${project}/flags/${flag}/config/${ENVIRONMENT}`;

try {
  const api = await account();
  await ensureProject(api);
  await ensureFlag(api, NEW_CHECKOUT, "New checkout", "The one-page express checkout.");
  await ensureFlag(api, FREE_SHIPPING, "Free shipping", "Free shipping where we have a warehouse.");

  await api.call<Config>("PATCH", config(NEW_CHECKOUT), {
    enabled: true,
    fallthroughValue: false,
    rolloutPercentage: 30,
  });
  await api.call("PUT", `${config(NEW_CHECKOUT)}/rules`, { rules: [] });
  await api.call("PUT", `${config(NEW_CHECKOUT)}/overrides`, {
    overrides: [{ userKey: "qa-tester", value: true }],
  });

  await api.call<Config>("PATCH", config(FREE_SHIPPING), {
    enabled: true,
    fallthroughValue: false,
    rolloutPercentage: 0,
  });
  await api.call("PUT", `${config(FREE_SHIPPING)}/rules`, {
    rules: [{ attribute: "country", operator: "IN", matchValues: ["LK", "IN"], resultValue: true }],
  });
  await api.call("PUT", `${config(FREE_SHIPPING)}/overrides`, { overrides: [] });

  const key = await serverKey(api);
  writeEnv({ FLAGLANE_DEMO_EMAIL: email, FLAGLANE_DEMO_PASSWORD: password, FLAGLANE_SDK_KEY: key });

  console.log(`Seeded project ${projectKey} on ${flaglaneUrl}, environment ${ENVIRONMENT}:
  ${NEW_CHECKOUT}   on, rolled out to 30%, and always on for qa-tester
  ${FREE_SHIPPING}  on where country is LK or IN
The demo account and a server key are in .env.

Next: npm start, then open http://localhost:${process.env.PORT ?? 3000}`);
} catch (error) {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
}

async function account(): Promise<Management> {
  const registered = await Management.anonymous().attempt("POST", "/api/auth/register", {
    email,
    password,
    displayName: "Demo shop",
  });
  if (registered.status === 409 && generated) {
    throw new Error(
      `${email} is already registered and .env has no password for it. Put its password in .env as ` +
        "FLAGLANE_DEMO_PASSWORD, or set FLAGLANE_DEMO_EMAIL to another address.",
    );
  }
  if (registered.status !== 201 && registered.status !== 409) {
    throw new ApiError("POST", "/api/auth/register", registered.status, registered.body);
  }
  return Management.signIn(email, password);
}

async function ensureProject(api: Management): Promise<void> {
  const projects = await api.call<{ key: string }[]>("GET", "/api/projects");
  if (projects.some((p) => p.key === projectKey)) {
    return;
  }
  const created = await api.attempt("POST", "/api/projects", { key: projectKey, name: "Demo shop" });
  if (created.status === 409) {
    throw new Error(
      `Another account on this instance owns the project key ${projectKey}. ` +
        "Set FLAGLANE_DEMO_PROJECT in .env to another key.",
    );
  }
  if (created.status !== 201) {
    throw new ApiError("POST", "/api/projects", created.status, created.body);
  }
}

async function ensureFlag(api: Management, key: string, name: string, description: string): Promise<void> {
  const flags = await api.call<{ key: string; archivedAt: string | null }[]>("GET", `${project}/flags`);
  const existing = flags.find((f) => f.key === key);
  if (existing === undefined) {
    await api.call("POST", `${project}/flags`, { key, name, description, clientSideVisible: false });
  } else if (existing.archivedAt !== null) {
    await api.call("POST", `${project}/flags/${key}/restore`);
  }
}

/** The key in .env if it is a live key of this project's environment; otherwise a new one. */
async function serverKey(api: Management): Promise<string> {
  const keysPath = `${project}/environments/${ENVIRONMENT}/keys`;
  const current = sdkKey();
  if (current !== "") {
    const keys = await api.call<{ prefix: string; revokedAt: string | null }[]>("GET", keysPath);
    if (keys.some((k) => k.revokedAt === null && current.startsWith(k.prefix))) {
      return current;
    }
  }
  const created = await api.call<{ key: string }>("POST", keysPath, { name: "demo-shop", type: "server" });
  return created.key;
}

/** Sets these variables in .env, keeping every other line, readable by this user only. */
function writeEnv(values: Record<string, string>): void {
  const lines = existsSync(envFile)
    ? readFileSync(envFile, "utf8").replace(/\n$/, "").split(/\r?\n/)
    : ["# Written by npm run seed. Gitignored: it holds the demo account's password and an SDK key."];
  for (const [name, value] of Object.entries(values)) {
    const line = `${name}=${value}`;
    const index = lines.findIndex((l) => l.startsWith(`${name}=`));
    if (index === -1) {
      lines.push(line);
    } else {
      lines[index] = line;
    }
  }
  writeFileSync(envFile, `${lines.join("\n")}\n`, { mode: 0o600 });
}
