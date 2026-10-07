import { useState, type FormEvent } from "react";
import { api, ApiError } from "../api/client";
import { Notice } from "../components/Notice";
import { sessions, type Ending } from "../session/session";

interface Props {
  readonly ended: Ending | null;
  /** Where to go once signed in. */
  readonly next: string;
  readonly onSignedIn: (next: string) => void;
}

const time = new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" });

/** FR-UI-001. Registration is here too, so that the dashboard is usable from the first visit. */
export function SignInPage({ ended, next, onSignedIn }: Props) {
  const [mode, setMode] = useState<"sign-in" | "register">("sign-in");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<ReadonlyMap<string, string>>(new Map());
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setFields(new Map());
    try {
      if (mode === "register") {
        await api("POST /api/auth/register", {}, {
          email,
          password,
          ...(displayName.trim() === "" ? {} : { displayName: displayName.trim() }),
        });
      }
      const token = await api("POST /api/auth/login", {}, { email, password });
      sessions.start(token.accessToken, email.trim().toLowerCase(), token.expiresAt);
      onSignedIn(next);
    } catch (caught) {
      const failure = caught instanceof ApiError ? caught : new ApiError(0, "Signing in failed.");
      setError(message(mode, failure));
      setFields(failure.fields);
    } finally {
      setBusy(false);
    }
  };

  const switchMode = () => {
    setMode(mode === "sign-in" ? "register" : "sign-in");
    setError(null);
    setFields(new Map());
  };

  return (
    <main className="sign-in">
      <h1 className="brand">Flaglane</h1>
      {ended !== null && <Notice tone={ended.reason === "signed-out" ? "info" : "warning"}>{ending(ended)}</Notice>}
      <form className="card" onSubmit={submit} noValidate>
        <h2>{mode === "sign-in" ? "Sign in" : "Create an account"}</h2>
        <label>
          Email
          <input
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            aria-invalid={fields.has("email")}
          />
          {fields.has("email") && <span className="field-error">{fields.get("email")}</span>}
        </label>
        {mode === "register" && (
          <label>
            <span>
              Name <span className="muted">(optional)</span>
            </span>
            <input
              autoComplete="name"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              aria-invalid={fields.has("displayName")}
            />
            {fields.has("displayName") && <span className="field-error">{fields.get("displayName")}</span>}
          </label>
        )}
        <label>
          Password
          <input
            type="password"
            autoComplete={mode === "sign-in" ? "current-password" : "new-password"}
            required
            minLength={mode === "register" ? 15 : undefined}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            aria-invalid={fields.has("password")}
          />
          {mode === "register" && <span className="hint">At least 15 characters. A passphrase works well.</span>}
          {fields.has("password") && <span className="field-error">{fields.get("password")}</span>}
        </label>
        {error !== null && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        <div className="actions">
          <button type="submit" className="primary" disabled={busy}>
            {mode === "sign-in" ? "Sign in" : "Create account and sign in"}
          </button>
          <button type="button" className="link" onClick={switchMode}>
            {mode === "sign-in" ? "Create an account" : "I already have an account"}
          </button>
        </div>
      </form>
      <p className="muted session-note">
        Your session lives in this tab only. Reloading the page, opening a new tab or closing this one
        signs you out, and signing in again brings you back to the page you were on. A session lasts
        at most eight hours.
      </p>
    </main>
  );
}

function ending({ reason, tokenExpiresAt }: Ending): string {
  switch (reason) {
    case "signed-out":
      return (
        "You signed out. This tab no longer holds your token. Flaglane cannot revoke a token, so a" +
        ` copy taken before you signed out would stay valid until ${time.format(tokenExpiresAt)}.`
      );
    case "expired":
      return "Your session reached its eight-hour limit. Sign in again to carry on.";
    case "rejected":
      return "Flaglane no longer accepts your session, so you have been signed out. Sign in again to carry on.";
  }
}

function message(mode: "sign-in" | "register", error: ApiError): string {
  if (error.status === 401) {
    return "That email and password do not match an account.";
  }
  if (error.status === 409 && mode === "register") {
    return "An account with that email already exists. Sign in instead.";
  }
  if (error.status === 400 && error.fields.size > 0) {
    return "Check the fields marked below.";
  }
  return error.message;
}
