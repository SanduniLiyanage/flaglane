import type { FormEvent } from "react";
import { MAX_OVERRIDES, newOverride, problemsOf, type OverrideDraft } from "./overrides";

interface Props {
  readonly drafts: readonly OverrideDraft[];
  readonly dirty: boolean;
  readonly saving: boolean;
  readonly busy: boolean;
  readonly readOnly: string | null;
  readonly onChange: (drafts: OverrideDraft[]) => void;
  readonly onSave: () => void;
  readonly onDiscard: () => void;
}

/**
 * Named users and the value each gets, staged and saved as one set (FR-RUL-001, FR-UI-007). An
 * override comes before every rule and the rollout, and applies to server keys only (FR-KEY-008).
 */
export function OverridesEditor({ drafts, dirty, saving, busy, readOnly, onChange, onSave, onDiscard }: Props) {
  const locked = readOnly !== null || saving || busy;
  const problems = problemsOf(drafts);

  const update = (id: number, change: Partial<Omit<OverrideDraft, "id">>) => {
    onChange(drafts.map((draft) => (draft.id === id ? { ...draft, ...change } : draft)));
  };
  const submit = (event: FormEvent) => {
    event.preventDefault();
    onSave();
  };

  return (
    <form className="card overrides" onSubmit={submit} aria-labelledby="overrides-heading">
      <h2 id="overrides-heading">User overrides</h2>
      <p className="hint">
        A named user gets the value given here before any rule or the rollout is consulted. Overrides apply
        to server keys only: a client key's ruleset carries none, because user keys identify real people, so
        in a browser target users with a rule on an attribute instead.
      </p>
      {drafts.length === 0 ? (
        <p className="muted">No overrides.</p>
      ) : (
        <table className="list overrides-table">
          <thead>
            <tr>
              <th scope="col">User key</th>
              <th scope="col">Gets</th>
              <th scope="col">
                <span className="visually-hidden">Remove</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {drafts.map((draft) => {
              const problem = problems.get(draft.id);
              return (
                <tr key={draft.id}>
                  <td>
                    <input
                      aria-label="User key"
                      value={draft.userKey}
                      disabled={locked}
                      maxLength={300}
                      onChange={(e) => update(draft.id, { userKey: e.target.value })}
                      aria-invalid={problem !== undefined}
                    />
                    {problem !== undefined && <span className="field-error">{problem}</span>}
                  </td>
                  <td>
                    <select
                      aria-label="Value"
                      value={String(draft.value)}
                      disabled={locked}
                      onChange={(e) => update(draft.id, { value: e.target.value === "true" })}
                    >
                      <option value="true">true</option>
                      <option value="false">false</option>
                    </select>
                  </td>
                  <td>
                    <button
                      type="button"
                      disabled={locked}
                      onClick={() => onChange(drafts.filter((other) => other.id !== draft.id))}
                    >
                      Remove
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      {readOnly !== null && <p className="reason">{readOnly}</p>}
      <div className="actions">
        <button
          type="button"
          onClick={() => onChange([...drafts, newOverride()])}
          disabled={locked || drafts.length >= MAX_OVERRIDES}
        >
          Add a user
        </button>
        <button type="submit" className="primary" disabled={!dirty || problems.size > 0 || locked}>
          {saving ? "Saving…" : "Save overrides"}
        </button>
        <button type="button" onClick={onDiscard} disabled={!dirty || locked}>
          Discard
        </button>
        {dirty && !saving && (
          <span className="muted">
            {problems.size > 0
              ? "Fix the entries marked above to save them."
              : "Unsaved changes. Nothing changes for users until you save."}
          </span>
        )}
      </div>
    </form>
  );
}
