import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { ConfigForm } from "../src/flags/ConfigForm";
import { KillSwitch } from "../src/flags/KillSwitch";
import type { Staged } from "../src/flags/staging";

const nothing = () => undefined;

function form(staged: Staged, overrides: Partial<Parameters<typeof ConfigForm>[0]> = {}): string {
  return renderToStaticMarkup(
    <ConfigForm
      staged={staged}
      dirty={false}
      saving={false}
      busy={false}
      readOnly={null}
      onChange={nothing}
      onSave={nothing}
      onDiscard={nothing}
      {...overrides}
    />,
  );
}

function rangeInput(markup: string): string {
  const input = markup.match(/<input[^>]*type="range"[^>]*>/)?.[0];
  if (input === undefined) {
    throw new Error("the form has no range input");
  }
  return input;
}

describe("the configuration form", () => {
  it("offers the rollout in whole percentages from 0 to 100", () => {
    const input = rangeInput(form({ fallthroughValue: false, rolloutPercentage: 30 }));

    expect(input).toContain('min="0"');
    expect(input).toContain('max="100"');
    expect(input).toContain('step="1"');
    expect(input).toContain('value="30"');
    expect(input).not.toContain("disabled");
  });

  it("disables the rollout while the fallthrough value is true, says why, and keeps its value", () => {
    const markup = form({ fallthroughValue: true, rolloutPercentage: 30 });
    const input = rangeInput(markup);

    expect(input).toContain('disabled=""');
    expect(input).toContain('value="30"');
    expect(input).toContain('aria-describedby="rollout-inert"');
    expect(markup).toContain("The rollout has no effect while the fallthrough value is true");
  });

  it("offers Save and Discard only once something is staged", () => {
    const clean = form({ fallthroughValue: false, rolloutPercentage: 30 });
    const dirty = form({ fallthroughValue: false, rolloutPercentage: 40 }, { dirty: true });

    expect(clean).toMatch(/<button type="submit"[^>]*disabled=""/);
    expect(dirty).not.toMatch(/<button type="submit"[^>]*disabled=""/);
    expect(dirty).toContain("Nothing changes for users until you save");
  });

  it("is read-only, with the reason, for an archived flag", () => {
    const markup = form({ fallthroughValue: false, rolloutPercentage: 30 }, { readOnly: "This flag is archived." });

    expect(markup.match(/<fieldset disabled="">/g)).toHaveLength(2);
    expect(markup).toContain("This flag is archived.");
  });
});

describe("the kill switch", () => {
  it("asks before it acts, rather than acting on the first click", () => {
    const markup = renderToStaticMarkup(
      <KillSwitch flag="new-checkout" environment="production" enabled busy={false} readOnly={null} onSet={nothing} />,
    );

    expect(markup).toContain("Turn off…");
    expect(markup).not.toContain("Everyone gets false");
  });
});
