// A thin fetch wrapper over the generated types (ADR-031): one function, typed by the operation it
// calls, that sends the session's token, ends the session on a 401, and turns a problem response
// into an ApiError the page can show.

import { sessions, type SessionStore } from "../session/session";
import type { Operations, ProblemDetail } from "./schema";

export type Operation = keyof Operations;

/** A response other than success, with the API's own explanation where it gave one. */
export class ApiError extends Error {
  readonly status: number;
  /** Per-field messages from a validation failure, by field name (docs/API.md). */
  readonly fields: ReadonlyMap<string, string>;

  constructor(status: number, message: string, fields: ReadonlyMap<string, string> = new Map()) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.fields = fields;
  }
}

type Args<K extends Operation> = Operations[K]["body"] extends never
  ? [params: Operations[K]["params"]]
  : [params: Operations[K]["params"], body: Operations[K]["body"]];

export interface Api {
  <K extends Operation>(operation: K, ...args: Args<K>): Promise<Operations[K]["response"]>;
}

/** A client over `fetch` that authenticates with `store`'s session. */
export function client(store: SessionStore = sessions, send: typeof fetch = (...a) => fetch(...a)): Api {
  return async (operation, ...args) => {
    const [method, template] = operation.split(" ") as [string, string];
    const [params, body] = args as [Record<string, unknown>, unknown?];
    const url = resolve(template, params);
    const token = store.state.session?.token;

    const headers: Record<string, string> = { accept: "application/json" };
    if (token !== undefined) {
      headers.authorization = `Bearer ${token}`;
    }
    if (args.length > 1) {
      headers["content-type"] = "application/json";
    }

    let response: Response;
    try {
      response = await send(url, {
        method,
        headers,
        ...(args.length > 1 ? { body: JSON.stringify(body) } : {}),
      });
    } catch {
      throw new ApiError(0, "Flaglane could not be reached. Check your connection and try again.");
    }

    if (response.status === 401 && token !== undefined) {
      // The token expired early, or the API's signing secret changed: either way it is over.
      store.end("rejected");
    }
    if (!response.ok) {
      throw await problem(response);
    }
    if (response.status === 204) {
      return undefined as never;
    }
    return (await response.json()) as never;
  };
}

/** Fills a path template's {variables} and puts every other parameter in the query string. */
export function resolve(template: string, params: Record<string, unknown>): string {
  const query = new URLSearchParams();
  const used = new Set<string>();
  const path = template.replace(/\{(\w+)\}/g, (_, name: string) => {
    const value = params[name];
    if (value === undefined || value === null || value === "") {
      throw new Error(`${template} needs ${name}`);
    }
    used.add(name);
    return encodeURIComponent(String(value));
  });
  for (const [name, value] of Object.entries(params)) {
    if (!used.has(name) && value !== undefined && value !== null) {
      query.set(name, String(value));
    }
  }
  const search = query.toString();
  return search === "" ? path : `${path}?${search}`;
}

async function problem(response: Response): Promise<ApiError> {
  const fallback = response.status === 429 ? "Too many attempts. Wait a moment and try again." : `The API answered ${response.status}.`;
  let detail: (ProblemDetail & { errors?: unknown }) | null = null;
  try {
    detail = (await response.json()) as ProblemDetail & { errors?: unknown };
  } catch {
    // Not a problem document; the status alone will have to do.
  }
  const fields = new Map<string, string>();
  if (detail !== null && Array.isArray(detail.errors)) {
    for (const error of detail.errors as unknown[]) {
      if (typeof error === "object" && error !== null) {
        const { field, message } = error as { field?: unknown; message?: unknown };
        if (typeof field === "string" && typeof message === "string") {
          fields.set(field, message);
        }
      }
    }
  }
  const message = typeof detail?.detail === "string" && detail.detail !== "" ? detail.detail : fallback;
  return new ApiError(response.status, message, fields);
}

/** The dashboard's client. */
export const api = client();
