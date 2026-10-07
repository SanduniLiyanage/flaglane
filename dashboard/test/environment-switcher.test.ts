import { describe, expect, it } from "vitest";
import { inEnvironment } from "../src/components/EnvironmentSwitcher";

const environments = ["development", "staging", "production"];

describe("switching environment", () => {
  it("keeps the page, a flag's included, and its query", () => {
    expect(inEnvironment("/projects/shop/development/flags/new-checkout", "production", environments)).toBe(
      "/projects/shop/production/flags/new-checkout",
    );
    expect(inEnvironment("/projects/shop/development/keys?x=1", "staging", environments)).toBe(
      "/projects/shop/staging/keys?x=1",
    );
  });

  it("goes nowhere for an environment the project does not have, or none", () => {
    // A select whose options had not loaded yet once produced "" and the address /projects/shop//flags.
    expect(inEnvironment("/projects/shop/development/flags", "", environments)).toBeNull();
    expect(inEnvironment("/projects/shop/development/flags", "qa", environments)).toBeNull();
  });

  it("goes nowhere for the environment it is already in", () => {
    expect(inEnvironment("/projects/shop/production/flags", "production", environments)).toBeNull();
  });
});
