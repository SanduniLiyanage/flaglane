import react from "@vitejs/plugin-react";
import type { Plugin } from "vite";
import { defineConfig } from "vitest/config";

// The page talks to its own origin only (ADR-031). In development the dev server proxies /api to
// the API, so the browser never makes a cross-origin request and the API's CORS policy, which
// admits none on /api/**, stays as it is. The proxy keeps the Host header the browser sent, so the
// API sees a same-origin request.
const api = process.env.FLAGLANE_API_URL ?? "http://localhost:8080";

/**
 * Scripts, styles and connections from the page's own origin only. Written into the built page
 * rather than the development one, whose dev server injects inline scripts the policy would block.
 * Spring Security's default headers already forbid framing (X-Frame-Options: DENY), which a meta
 * policy cannot express.
 */
export const CONTENT_SECURITY_POLICY = [
  "default-src 'none'",
  "script-src 'self'",
  "style-src 'self'",
  "connect-src 'self'",
  "img-src 'self'",
  "base-uri 'none'",
  "form-action 'self'",
  "object-src 'none'",
].join("; ");

function contentSecurityPolicy(): Plugin {
  return {
    name: "flaglane-content-security-policy",
    apply: "build",
    transformIndexHtml: () => [
      {
        tag: "meta",
        attrs: { "http-equiv": "Content-Security-Policy", content: CONTENT_SECURITY_POLICY },
        injectTo: "head-prepend",
      },
    ],
  };
}

export default defineConfig({
  plugins: [react(), contentSecurityPolicy()],
  server: {
    proxy: { "/api": { target: api } },
  },
  build: {
    // Nothing inlined as a data: URI, which the policy above does not admit.
    assetsInlineLimit: 0,
  },
  test: {
    environment: "node",
    include: ["test/**/*.test.{ts,tsx}"],
  },
});
