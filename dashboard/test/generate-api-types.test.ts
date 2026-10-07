import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { generate, UnsupportedSchema } from "../scripts/generate-api-types.ts";

const read = (path: string) => readFileSync(new URL(path, import.meta.url), "utf8");

function document(schemas: Record<string, unknown>, paths: Record<string, unknown> = {}) {
  return { openapi: "3.1.0", paths, components: { schemas } } as never;
}

describe("the API types generator", () => {
  it("produces exactly the committed schema.ts from the committed snapshot", () => {
    const generated = generate(JSON.parse(read("../openapi.json")));

    expect(generated).toBe(read("../src/api/schema.ts").replaceAll("\r\n", "\n"));
  });

  it("types a nullable field as nullable and an unrequired one as optional", () => {
    const generated = generate(
      document({
        Thing: {
          type: "object",
          properties: { key: { type: "string" }, note: { type: ["string", "null"] } },
          required: ["key"],
        },
      }),
    );

    expect(generated).toContain("key: string;");
    expect(generated).toContain("note?: string | null;");
  });

  it("types an operation's parameters, body and response", () => {
    const generated = generate(
      document(
        { Thing: { type: "object", properties: { key: { type: "string" } }, required: ["key"] } },
        {
          "/api/things/{thingKey}": {
            patch: {
              parameters: [{ name: "thingKey", in: "path", required: true, schema: { type: "string" } }],
              requestBody: { content: { "application/json": { schema: { $ref: "#/components/schemas/Thing" } } } },
              responses: {
                "200": { description: "ok", content: { "application/json": { schema: { $ref: "#/components/schemas/Thing" } } } },
                "404": { description: "missing" },
              },
            },
          },
          "/sdk/config": { get: { responses: { "200": { description: "not the dashboard's" } } } },
        },
      ),
    );

    expect(generated).toContain('"PATCH /api/things/{thingKey}": {');
    expect(generated).toContain("params: { thingKey: string };");
    expect(generated).toContain("body: Thing;");
    expect(generated).toContain("response: Thing;");
    expect(generated).not.toContain("/sdk/config");
  });

  it.each([
    ["a composition", { oneOf: [{ type: "string" }, { type: "integer" }] }],
    ["a reference outside the components", { $ref: "https://example.com/schema.json" }],
    ["a type it does not know", { type: "null" }],
    ["two types at once", { type: ["string", "integer"] }],
    ["an enum of numbers", { type: "integer", enum: [1, 2] }],
    ["an object with nothing in it", { type: "object" }],
  ])("refuses %s rather than guessing", (_, schema) => {
    expect(() => generate(document({ Odd: schema }))).toThrow(UnsupportedSchema);
  });

  it("refuses an operation without exactly one success response", () => {
    const paths = {
      "/api/things": { get: { responses: { "200": { description: "a" }, "201": { description: "b" } } } },
    };

    expect(() => generate(document({}, paths))).toThrow(/2 success responses/);
  });

  it("refuses a body that is not JSON alone", () => {
    const paths = {
      "/api/things": {
        get: { responses: { "200": { description: "a", content: { "*/*": { schema: { type: "string" } } } } } },
      },
    };

    expect(() => generate(document({}, paths))).toThrow(/not application\/json alone/);
  });
});
