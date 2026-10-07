import type { FormEvent } from "react";
import {
  describe,
  MAX_RULES,
  moved,
  newRule,
  OPERATORS,
  problemsOf,
  warningOf,
  type Operator,
  type RuleDraft,
  type ValueType,
} from "./rules";

interface Props {
  readonly drafts: readonly RuleDraft[];
  readonly dirty: boolean;
  readonly saving: boolean;
  readonly busy: boolean;
  readonly readOnly: string | null;
  /** Problems the API reported on the last save, by rule position. */
  readonly refused: ReadonlyMap<number, string>;
  readonly onChange: (drafts: RuleDraft[]) => void;
  readonly onSave: () => void;
  readonly onDiscard: () => void;
}

const TYPES: Record<ValueType, string> = { string: "text", number: "number", boolean: "true or false" };

/**
 * The flag's targeting rules in one environment, staged and saved as one ordered list (FR-RUL-004,
 * FR-UI-007). The first rule that matches decides; the order is the priority.
 */
export function RulesEditor({ drafts, dirty, saving, busy, readOnly, refused, onChange, onSave, onDiscard }: Props) {
  const locked = readOnly !== null || saving || busy;
  const problems = drafts.map(problemsOf);
  const invalid = problems.some((p) => Object.keys(p).length > 0);

  const update = (index: number, change: Partial<Omit<RuleDraft, "id">>) => {
    onChange(drafts.map((draft, i) => (i === index ? { ...draft, ...change } : draft)));
  };
  const setOperator = (index: number, operator: Operator) => {
    // The text operators compare strings only, so the type follows rather than becoming an error.
    update(index, OPERATORS[operator].stringOnly ? { operator, type: "string" } : { operator });
  };
  const setType = (index: number, type: ValueType) => {
    const draft = drafts[index] as RuleDraft;
    update(index, type === "boolean" && draft.values.trim() === "" ? { type, values: "true" } : { type });
  };
  const submit = (event: FormEvent) => {
    event.preventDefault();
    onSave();
  };

  return (
    <form className="card rules" onSubmit={submit} aria-labelledby="rules-heading">
      <h2 id="rules-heading">Rules</h2>
      <p className="hint">
        Tested from the top for users no override decides; the first rule that matches gives its value, and
        users no rule matches go on to the rollout. A rule never matches a user without the attribute, whatever
        its operator.
      </p>
      {drafts.length === 0 && <p className="muted">No rules.</p>}
      <ol className="rule-list">
        {drafts.map((draft, index) => {
          const problem = problems[index] ?? {};
          const operator = OPERATORS[draft.operator];
          const warning = warningOf(draft);
          const fromApi = refused.get(index);
          return (
            <li key={draft.id}>
              <fieldset className="rule" disabled={locked}>
                <legend>
                  Rule {index + 1} <span className="muted">· {describe(draft)}</span>
                </legend>
                <div className="rule-fields">
                  <label>
                    Attribute
                    <input
                      value={draft.attribute}
                      maxLength={200}
                      placeholder="country"
                      onChange={(e) => update(index, { attribute: e.target.value })}
                      aria-invalid={problem.attribute !== undefined}
                    />
                  </label>
                  <label>
                    Operator
                    <select value={draft.operator} onChange={(e) => setOperator(index, e.target.value as Operator)}>
                      {(Object.keys(OPERATORS) as Operator[]).map((name) => (
                        <option key={name} value={name}>
                          {OPERATORS[name].label}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Values are
                    <select
                      value={draft.type}
                      disabled={operator.stringOnly}
                      onChange={(e) => setType(index, e.target.value as ValueType)}
                    >
                      {(Object.keys(TYPES) as ValueType[]).map((type) => (
                        <option key={type} value={type}>
                          {TYPES[type]}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="rule-values">
                    {operator.shape === "list" ? "Values, one per line" : "Value"}
                    {operator.shape === "list" ? (
                      <textarea
                        rows={Math.min(6, Math.max(2, draft.values.split("\n").length))}
                        value={draft.values}
                        onChange={(e) => update(index, { values: e.target.value })}
                        aria-invalid={problem.values !== undefined}
                      />
                    ) : draft.type === "boolean" ? (
                      <select value={draft.values} onChange={(e) => update(index, { values: e.target.value })}>
                        <option value="true">true</option>
                        <option value="false">false</option>
                      </select>
                    ) : (
                      <input
                        value={draft.values}
                        inputMode={draft.type === "number" ? "decimal" : undefined}
                        onChange={(e) => update(index, { values: e.target.value })}
                        aria-invalid={problem.values !== undefined}
                      />
                    )}
                  </label>
                  <label>
                    Then serve
                    <select
                      value={String(draft.resultValue)}
                      onChange={(e) => update(index, { resultValue: e.target.value === "true" })}
                    >
                      <option value="true">true</option>
                      <option value="false">false</option>
                    </select>
                  </label>
                </div>
                {problem.attribute !== undefined && <p className="field-error">{problem.attribute}</p>}
                {problem.values !== undefined && <p className="field-error">{problem.values}</p>}
                {fromApi !== undefined && <p className="field-error">Flaglane refused this rule: {fromApi}</p>}
                {warning !== null && <p className="reason">{warning}</p>}
                <div className="actions">
                  <button type="button" onClick={() => onChange(moved(drafts, index, -1))} disabled={index === 0}>
                    Move up
                  </button>
                  <button
                    type="button"
                    onClick={() => onChange(moved(drafts, index, 1))}
                    disabled={index === drafts.length - 1}
                  >
                    Move down
                  </button>
                  <button type="button" onClick={() => onChange(drafts.filter((_, i) => i !== index))}>
                    Remove
                  </button>
                </div>
              </fieldset>
            </li>
          );
        })}
      </ol>
      {readOnly !== null && <p className="reason">{readOnly}</p>}
      <div className="actions">
        <button
          type="button"
          onClick={() => onChange([...drafts, newRule()])}
          disabled={locked || drafts.length >= MAX_RULES}
          title={drafts.length >= MAX_RULES ? `At most ${MAX_RULES} rules` : undefined}
        >
          Add a rule
        </button>
        <button type="submit" className="primary" disabled={!dirty || invalid || locked}>
          {saving ? "Saving…" : "Save rules"}
        </button>
        <button type="button" onClick={onDiscard} disabled={!dirty || locked}>
          Discard
        </button>
        {dirty && !saving && (
          <span className="muted">
            {invalid ? "Fix the rules marked above to save them." : "Unsaved changes. Nothing changes for users until you save."}
          </span>
        )}
      </div>
    </form>
  );
}
