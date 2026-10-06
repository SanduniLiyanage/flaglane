// The shop. It asks Flaglane two questions per page view — which checkout, and whether shipping is
// free — and answers both in process from the ruleset the SDK holds. Nothing here handles Flaglane
// being down, because nothing has to: the SDK keeps answering from the last ruleset it had.

import { createServer } from "node:http";
import { FlaglaneClient } from "@flaglane/sdk";
import { renderPage } from "./page.ts";
import { flaglaneUrl, port, sdkKey } from "./settings.ts";
import { SdkLog, findShopper, viewFor } from "./shop.ts";

const log = new SdkLog();
const key = sdkKey();
const flags = await FlaglaneClient.init({ sdkKey: key, baseUrl: flaglaneUrl, logger: log });

const server = createServer((request, response) => {
  const url = new URL(request.url ?? "/", "http://localhost");
  if (request.method !== "GET") {
    response.writeHead(405, { allow: "GET" }).end();
    return;
  }
  const view = viewFor(flags, log, findShopper(url.searchParams.get("shopper")), key !== "");
  if (url.pathname === "/") {
    response.writeHead(200, { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" });
    response.end(renderPage(view));
  } else if (url.pathname === "/state.json") {
    // What the page shows, as data, for npm run verify.
    response.writeHead(200, { "content-type": "application/json", "cache-control": "no-store" });
    response.end(JSON.stringify(view));
  } else {
    response.writeHead(404, { "content-type": "text/plain" }).end("Not found");
  }
});

server.listen(port, () => {
  console.log(`Hilltop Tea is open on http://localhost:${port}`);
  console.log(
    flags.ready
      ? `Flags from ${flaglaneUrl}, ruleset version ${flags.version}`
      : `No ruleset from ${flaglaneUrl} yet; every flag is at its fallback until one arrives`,
  );
});

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.once(signal, () => {
    flags.close();
    server.close();
    server.closeAllConnections();
  });
}
