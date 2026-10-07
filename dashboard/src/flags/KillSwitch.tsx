import { useState } from "react";

interface Props {
  readonly flag: string;
  readonly environment: string;
  readonly enabled: boolean;
  readonly busy: boolean;
  /** Why it may not be used, or null when it may. */
  readonly readOnly: string | null;
  readonly onSet: (enabled: boolean) => void;
}

/**
 * Turns the flag off or on in one environment: one confirmed action that saves at once, apart from
 * the staged form, because the control for an incident should not wait on a form (ADR-031). A flag
 * that is off returns false to everyone, whatever else is configured (ADR-009).
 */
export function KillSwitch({ flag, environment, enabled, busy, readOnly, onSet }: Props) {
  const [confirming, setConfirming] = useState(false);
  const target = !enabled;

  return (
    <section className={enabled ? "card kill-switch on" : "card kill-switch off"} aria-labelledby="kill-switch">
      <div className="kill-switch-state">
        <h2 id="kill-switch">{enabled ? "On" : "Off"}</h2>
        <p className="muted">
          {enabled
            ? `Serving its overrides, rules, rollout and fallthrough value in ${environment}.`
            : `Returning false to everyone in ${environment}.`}
        </p>
      </div>
      {confirming ? (
        <div className="confirm" role="group" aria-label="Confirm">
          <p>
            {target
              ? `Turn ${flag} on in ${environment}? Users start getting its overrides, rules, rollout and fallthrough value as SDKs pick it up, within about five seconds.`
              : `Turn ${flag} off in ${environment}? Everyone gets false as SDKs pick it up, within about five seconds.`}
          </p>
          <div className="actions">
            <button
              type="button"
              className={target ? "primary" : "danger"}
              disabled={busy}
              onClick={() => {
                setConfirming(false);
                onSet(target);
              }}
            >
              {target ? "Turn on" : "Turn off"}
            </button>
            <button type="button" onClick={() => setConfirming(false)} disabled={busy}>
              Cancel
            </button>
          </div>
        </div>
      ) : (
        <button
          type="button"
          className={enabled ? "danger" : "primary"}
          disabled={busy || readOnly !== null}
          title={readOnly ?? undefined}
          onClick={() => setConfirming(true)}
        >
          {busy ? "Saving…" : enabled ? "Turn off…" : "Turn on…"}
        </button>
      )}
    </section>
  );
}
