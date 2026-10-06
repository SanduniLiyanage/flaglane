// A small client for the parts of the management API the demo scripts use. It stands in for the
// dashboard: every call here is one a dashboard button would make.

import { flaglaneUrl } from "./settings.ts";

export interface Config {
  readonly flagKey: string;
  readonly environment: string;
  readonly enabled: boolean;
  readonly fallthroughValue: boolean;
  readonly rolloutPercentage: number;
  readonly rolloutSalt: string;
}

/** A response with a status the caller did not expect, with the problem detail Flaglane sent. */
export class ApiError extends Error {
  readonly status: number;

  constructor(method: string, path: string, status: number, problem: unknown) {
    super(`${method} ${path} answered ${status}${summary(problem)}`);
    this.name = "ApiError";
    this.status = status;
  }
}

export class Management {
  readonly #token: string | undefined;

  private constructor(token: string | undefined) {
    this.#token = token;
  }

  static anonymous(): Management {
    return new Management(undefined);
  }

  static async signIn(email: string, password: string): Promise<Management> {
    const session = await Management.anonymous().call<{ accessToken: string }>(
      "POST",
      "/api/auth/login",
      { email, password },
    );
    return new Management(session.accessToken);
  }

  /** Calls the API and returns the parsed body, or throws an ApiError for any status not listed. */
  async call<T>(method: string, path: string, body?: unknown, expected: readonly number[] = [200, 201, 204]): Promise<T> {
    const response = await this.send(method, path, body);
    const text = await response.text();
    const parsed: unknown = text === "" ? undefined : JSON.parse(text);
    if (!expected.includes(response.status)) {
      throw new ApiError(method, path, response.status, parsed);
    }
    return parsed as T;
  }

  /** As `call`, but returns the status alongside the body so the caller can branch on it. */
  async attempt<T>(method: string, path: string, body?: unknown): Promise<{ status: number; body: T }> {
    const response = await this.send(method, path, body);
    const text = await response.text();
    return { status: response.status, body: (text === "" ? undefined : JSON.parse(text)) as T };
  }

  private async send(method: string, path: string, body: unknown): Promise<Response> {
    const headers: Record<string, string> = { accept: "application/json" };
    if (this.#token !== undefined) {
      headers.authorization = `Bearer ${this.#token}`;
    }
    if (body !== undefined) {
      headers["content-type"] = "application/json";
    }
    try {
      return await fetch(`${flaglaneUrl}${path}`, {
        method,
        headers,
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
    } catch (error) {
      throw new Error(`Flaglane is not reachable at ${flaglaneUrl}. Is it running?`, { cause: error });
    }
  }
}

function summary(problem: unknown): string {
  if (problem === null || typeof problem !== "object") {
    return "";
  }
  const { detail, errors } = problem as { detail?: unknown; errors?: unknown };
  let text = typeof detail === "string" ? `: ${detail}` : "";
  if (Array.isArray(errors)) {
    for (const error of errors as { field?: unknown; message?: unknown }[]) {
      text += `\n  ${String(error.field)}: ${String(error.message)}`;
    }
  }
  return text;
}
