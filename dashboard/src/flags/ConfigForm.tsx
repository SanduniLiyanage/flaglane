import type { FormEvent } from "react";
import { percentageFrom, rolloutIsInert, type Staged } from "./staging";

interface Props {
  readonly staged: Staged;
  readonly dirty: boolean;
  /** Whether this form's changes are being saved. */
  readonly saving: boolean;
  /** Whether anything else on the page is being saved, which holds the form until it is done. */
  readonly busy: boolean;
  /** Why nothing may be edited, or null when it may. */
  readonly readOnly: string | null;
  readonly onChange: (staged: Staged) => void;
  readonly onSave: () => void;
  readonly onDiscard: () => void;
}

/**
 * The fallthrough value and the rollout, staged and saved together (FR-UI-007, ADR-031). The
 * rollout is a whole percentage on a range input that steps by 1, so no fraction can be produced.
 * While the staged fallthrough value is true the rollout changes nothing, and the slider is disabled
 * with the reason beside it; the percentage it holds is kept, not reset.
 */
export function ConfigForm({ staged, dirty, saving, busy, readOnly, onChange, onSave, onDiscard }: Props) {
  const inert = rolloutIsInert(staged);
  const locked = readOnly !== null || saving || busy;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    onSave();
  };

  return (
    <form className="card config" onSubmit={submit}>
      <h2>Rollout and fallthrough</h2>
      <fieldset disabled={locked}>
        <legend>Fallthrough value</legend>
        <p className="hint">What a user gets when the flag is on and no override, rule or rollout gave them anything.</p>
        <label className="inline">
          <input
            type="radio"
            name="fallthrough"
            checked={!staged.fallthroughValue}
            onChange={() => onChange({ ...staged, fallthroughValue: false })}
          />
          <code>false</code>
        </label>
        <label className="inline">
          <input
            type="radio"
            name="fallthrough"
            checked={staged.fallthroughValue}
            onChange={() => onChange({ ...staged, fallthroughValue: true })}
          />
          <code>true</code>
        </label>
      </fieldset>

      <fieldset disabled={locked}>
        <legend>Rollout</legend>
        <div className="rollout">
          <input
            id="rollout"
            type="range"
            min={0}
            max={100}
            step={1}
            value={staged.rolloutPercentage}
            disabled={inert}
            aria-describedby={inert ? "rollout-inert" : "rollout-meaning"}
            onChange={(event) => onChange({ ...staged, rolloutPercentage: percentageFrom(event.target.value) })}
          />
          <output htmlFor="rollout" className={inert ? "muted" : undefined}>
            {staged.rolloutPercentage}%
          </output>
        </div>
        {inert ? (
          <p id="rollout-inert" className="reason">
            The rollout has no effect while the fallthrough value is true: everyone it does not reach gets
            true anyway. Its {staged.rolloutPercentage}% is kept for when the fallthrough value is false.
          </p>
        ) : (
          <p id="rollout-meaning" className="hint">
            Of the users no override or rule decides, {staged.rolloutPercentage}% get true, chosen by
            their key. Raising it never takes the flag from anyone who already has it.
          </p>
        )}
      </fieldset>

      {readOnly !== null && <p className="reason">{readOnly}</p>}
      <div className="actions">
        <button type="submit" className="primary" disabled={!dirty || locked}>
          {saving ? "Saving…" : "Save"}
        </button>
        <button type="button" onClick={onDiscard} disabled={!dirty || locked}>
          Discard
        </button>
        {dirty && !saving && <span className="muted">Unsaved changes. Nothing changes for users until you save.</span>}
      </div>
    </form>
  );
}
