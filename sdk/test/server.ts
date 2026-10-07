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
