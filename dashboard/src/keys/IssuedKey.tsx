import { useState } from "react";
import type { IssuedApiKeyResponse } from "../api/schema";
import { useLeaveGuard } from "../router";

const LEAVE = "The new key is shown only here, and only once. Leave without copying it?";

/**
 * The plaintext key, in the one response that carries it (FR-KEY-002, FR-UI-005). Flaglane keeps a
 * hash and a prefix, so nothing can show the key again: once this is dismissed, or the page left, it
 * is gone, and the page says so rather than offering to reveal it later.
 */
export function IssuedKey({ issued, onDone }: { issued: IssuedApiKeyResponse; onDone: () => void }) {
  const [copied, setCopied] = useState<"no" | "yes" | "failed">("no");
  useLeaveGuard(LEAVE);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(issued.key);
      setCopied("yes");
    } catch {
      setCopied("failed");
    }
  };

  return (
    <section className="card issued" aria-labelledby="issued-heading">
      <h2 id="issued-heading">Copy your new key now</h2>
      <p>
        This is the only time Flaglane shows <strong>{issued.name}</strong>. It stores only a hash of the key,
        so it cannot show it again, and nor can anyone else. If it is lost, issue another and revoke this one.
      </p>
      <div className="issued-key">
        <input readOnly aria-label="The new key" value={issued.key} onFocus={(event) => event.target.select()} />
        <button type="button" className="primary" onClick={copy}>
          Copy
        </button>
      </div>
      {copied === "yes" && <p className="muted">Copied to the clipboard.</p>}
      {copied === "failed" && (
        <p className="field-error">The browser did not allow copying. Select the key above and copy it yourself.</p>
      )}
      <div className="actions">
        <button type="button" onClick={onDone}>
          I have stored it. Hide the key
        </button>
      </div>
    </section>
  );
}
