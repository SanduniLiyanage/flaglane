import { describe, expect, it } from "vitest";
import { nextFrom, signInFor } from "../src/App";

describe("returning after sign-in", () => {
  it("returns to the page that asked", () => {
    expect(signInFor("/projects/storefront/production/flags")).toBe(
      "/sign-in?next=%2Fprojects%2Fstorefront%2Fproduction%2Fflags",
    );
    expect(nextFrom(signInFor("/projects/storefront/production/flags"))).toBe("/projects/storefront/production/flags");
  });

  it("goes to the projects when nothing asked", () => {
    expect(signInFor("/")).toBe("/sign-in");
    expect(nextFrom("/sign-in")).toBe("/projects");
  });

  it.each(["https://elsewhere.example/", "//elsewhere.example/", "/\\elsewhere.example/", "projects"])(
    "never leaves this origin for %s",
    (next) => {
      expect(nextFrom(`/sign-in?next=${encodeURIComponent(next)}`)).toBe("/projects");
    },
  );
});
