import {
  type IncomingHttpHeaders,
  type IncomingMessage,
  type ServerResponse,
  createServer,
} from "node:http";
import type { AddressInfo } from "node:net";
import { gzipSync } from "node:zlib";

/** A real HTTP server standing in for Flaglane, which a test can stop, hang or misbehave. */
export interface TestServer {
  readonly url: string;
  readonly requests: { url: string; headers: IncomingHttpHeaders }[];
  close(): Promise<void>;
}

export async function startServer(
  handler: (request: IncomingMessage, response: ServerResponse) => void,
): Promise<TestServer> {
  const requests: { url: string; headers: IncomingHttpHeaders }[] = [];
  const server = createServer((request, response) => {
    requests.push({ url: request.url ?? "", headers: request.headers });
    handler(request, response);
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as AddressInfo;
  return {
    url: `http://127.0.0.1:${port}`,
    requests,
    close: () =>
      new Promise<void>((resolve) => {
        server.closeAllConnections();
        server.close(() => resolve());
      }),
  };
}

/**
 * The requests for the ruleset, without the SDK's attempts at a stream, for tests about polling.
 */
export function rulesetRequests(server: TestServer): TestServer["requests"] {
  return server.requests.filter((request) => request.url === "/sdk/config");
}

/** A URL on which nothing listens: a port that was free a moment ago. */
export async function unreachableUrl(): Promise<string> {
  const server = await startServer(() => {});
  await server.close();
  return server.url;
}

/**
 * Serves a ruleset with its ETag, answering 304 to a matching If-None-Match. With `gzip`, it sends
 * the body gzipped to a request that accepts gzip, as Flaglane does, with the same ETag.
 */
export function servingRuleset(ruleset: () => { version: number }, keyType = "server", gzip = false) {
  return (request: IncomingMessage, response: ServerResponse) => {
    const current = ruleset();
    const etag = `"${current.version}-${keyType}"`;
    response.setHeader("cache-control", "private, no-store");
    response.setHeader("vary", gzip ? "Authorization, Accept-Encoding" : "Authorization");
    response.setHeader("etag", etag);
    if (request.headers["if-none-match"] === etag) {
      response.statusCode = 304;
      response.end();
      return;
    }
    response.setHeader("content-type", "application/json");
    if (gzip && /\bgzip\b/.test(request.headers["accept-encoding"] ?? "")) {
      response.setHeader("content-encoding", "gzip");
      response.end(gzipSync(JSON.stringify(current)));
      return;
    }
    response.end(JSON.stringify(current));
  };
}

/** A stand-in for a Flaglane that streams: the ruleset at `/sdk/config`, events at `/sdk/stream`. */
export interface StreamingServer extends TestServer {
  /** Streams open now. */
  readonly open: Set<ServerResponse>;
  /** Stream requests received, answered or not. */
  streamRequests(): number;
  /** Writes text to every open stream, as is. */
  send(text: string): void;
  /** Tells every open stream that the ruleset is now at `version`, as Flaglane does. */
  announce(version: number): void;
  /** Ends every open stream, as Flaglane does at a stream's lifetime. */
  endStreams(): void;
}

/**
 * Serves the ruleset and a stream whose first event is the current version, as Flaglane does
 * (docs/API.md). `onStream` may answer a stream request itself instead, by returning true.
 */
export async function startStreamingServer(
  ruleset: () => { version: number },
  onStream?: (request: IncomingMessage, response: ServerResponse) => boolean,
): Promise<StreamingServer> {
  const open = new Set<ServerResponse>();
  const config = servingRuleset(ruleset);
  const server = await startServer((request, response) => {
    if (request.url !== "/sdk/stream") {
      config(request, response);
      return;
    }
    if (onStream?.(request, response)) {
      return;
    }
    response.writeHead(200, { "content-type": "text/event-stream", "cache-control": "private, no-store" });
    response.write(changed(ruleset().version));
    open.add(response);
    response.on("close", () => open.delete(response));
  });
  return {
    ...server,
    open,
    streamRequests: () => server.requests.filter((request) => request.url === "/sdk/stream").length,
    send: (text) => open.forEach((response) => response.write(text)),
    announce: (version) => open.forEach((response) => response.write(changed(version))),
    endStreams: () => open.forEach((response) => response.end()),
  };
}

function changed(version: number): string {
  return `event: ruleset-changed\nid: ${version}\ndata: {"environment":"production","version":${version}}\n\n`;
}

/** Waits for a condition without a fixed sleep; fails loudly if it never holds. */
export async function waitFor(condition: () => boolean, timeoutMs = 3_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!condition()) {
    if (Date.now() > deadline) {
      throw new Error(`condition not met within ${timeoutMs} ms`);
    }
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}
