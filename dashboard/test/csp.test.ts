import { describe, expect, it } from "vitest";
import { CONTENT_SECURITY_POLICY } from "../vite.config";

describe("the built page's content security policy", () => {
  it("admits scripts, styles and connections from the page's own origin only", () => {
    expect(CONTENT_SECURITY_POLICY).toContain("script-src 'self'");
    expect(CONTENT_SECURITY_POLICY).toContain("style-src 'self'");
    expect(CONTENT_SECURITY_POLICY).toContain("connect-src 'self'");
    expect(CONTENT_SECURITY_POLICY).not.toMatch(/unsafe-|\*|data:|https?:/);
  });
});
