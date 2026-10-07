import { describe, expect, it } from "vitest";
import { match, path } from "../src/router";

describe("matching a route", () => {
  it("names the segments the pattern names", () => {
    expect(match("/projects/:project/:environment/flags/:flag", "/projects/storefront/production/flags/new-checkout")).toEqual({
      project: "storefront",
      environment: "production",
      flag: "new-checkout",
    });
  });

  it("ignores the query string and a trailing slash", () => {
    expect(match("/projects/:project", "/projects/storefront/?tab=1")).toEqual({ project: "storefront" });
  });

  it("does not match a path of another length or another literal", () => {
    expect(match("/projects/:project", "/projects/storefront/production")).toBeNull();
    expect(match("/projects/:project/:environment/flags", "/projects/storefront/production/keys")).toBeNull();
  });

  it("does not match a segment that is not valid percent-encoding", () => {
    expect(match("/projects/:project", "/projects/%E0%A4%A")).toBeNull();
  });

  it("builds a path from encoded segments", () => {
    expect(path("projects", "storefront", "a/b")).toBe("/projects/storefront/a%2Fb");
  });
});
