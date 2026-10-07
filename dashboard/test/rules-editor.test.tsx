import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { OverridesEditor } from "../src/flags/OverridesEditor";
import { newOverride } from "../src/flags/overrides";
import { newRule, type RuleDraft } from "../src/flags/rules";
import { RulesEditor } from "../src/flags/RulesEditor";

const nothing = () => undefined;

function rules(drafts: RuleDraft[], change: Partial<Parameters<typeof RulesEditor>[0]> = {}): string {
  return renderToStaticMarkup(
    <RulesEditor
      drafts={drafts}
      dirty
      saving={false}
      busy={false}
      readOnly={null}
      refused={new Map()}
      onChange={nothing}
      onSave={nothing}
      onDiscard={nothing}
      {...change}
    />,
  );
}

const rule = (change: Partial<RuleDraft>): RuleDraft => ({ ...newRule(), attribute: "country", values: "LK", ...change });

describe("the rules editor", () => {
  it("takes a list operator's values one per line and a single value in one field", () => {
    const markup = rules([rule({ operator: "IN", values: "LK\nIN" }), rule({ operator: "EQUALS", values: "LK" })]);

    expect(markup).toContain("Values, one per line");
    expect(markup.match(/<textarea/g)).toHaveLength(1);
    expect(markup).toContain("Rule 1");
    expect(markup).toContain("country is one of LK, IN → true");
  });

  it("fixes the type of a text operator's value to text", () => {
    const markup = rules([rule({ operator: "CONTAINS", values: "@example.com" })]);

    expect(markup).toMatch(/<select disabled="">\s*<option value="string" selected="">text<\/option>/);
  });

  it("says what is wrong beside the rule, and will not save until it is fixed", () => {
    const markup = rules([rule({ attribute: "" })]);

    expect(markup).toContain("Name the attribute the rule compares.");
    expect(markup).toMatch(/<button type="submit" class="primary" disabled="">/);
    expect(markup).toContain("Fix the rules marked above to save them.");
  });

  it("shows what Flaglane refused, against the rule it named", () => {
    const markup = rules([rule({}), rule({})], { refused: new Map([[1, "IN match values are not all of one type"]]) });

    expect(markup).toContain("Flaglane refused this rule: IN match values are not all of one type");
  });

  it("cannot move the first rule up or the last down", () => {
    const markup = rules([rule({}), rule({})]);

    expect(markup.match(/<button type="button" disabled="">Move up<\/button>/g)).toHaveLength(1);
    expect(markup.match(/<button type="button" disabled="">Move down<\/button>/g)).toHaveLength(1);
  });
});

describe("the overrides editor", () => {
  it("says overrides reach server keys only", () => {
    const markup = renderToStaticMarkup(
      <OverridesEditor
        drafts={[{ ...newOverride(), userKey: "u-1042" }]}
        dirty={false}
        saving={false}
        busy={false}
        readOnly={null}
        onChange={nothing}
        onSave={nothing}
        onDiscard={nothing}
      />,
    );

    expect(markup).toContain("Overrides apply to server keys only");
    expect(markup).toContain('value="u-1042"');
  });
});
