import { useState, type FormEvent } from "react";
import { api, ApiError } from "../api/client";
import type { ApiKeyResponse, IssuedApiKeyResponse } from "../api/schema";
import { useLoad } from "../api/useLoad";
import { Layout } from "../components/Layout";
import { Notice } from "../components/Notice";
import { IssuedKey } from "../keys/IssuedKey";
import { KEY_TYPES, ordered, revokeWarning, type KeyType } from "../keys/apiKeys";

const when = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" });

/** FR-UI-005: an environment's API keys, issued once, listed by prefix, revoked for good. */
export function KeysPage({ project, environment }: { project: string; environment: string }) {
  const keys = useLoad(`${project}/${environment}/keys`, () =>
    api("GET /api/projects/{projectKey}/environments/{envKey}/keys", { projectKey: project, envKey: environment }),
  );
  const [issued, setIssued] = useState<IssuedApiKeyResponse | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  return (
    <Layout project={{ key: project, environment }} tab="keys">
      <div className="heading">
        <h1>API keys</h1>
        <span className="muted">for {environment}</span>
      </div>
      <p className="muted">
        An SDK authenticates with a key, which reads this environment's ruleset and nothing else. Flaglane
        keeps only a hash of each key and its first characters, so a key is shown once, when it is issued.
      </p>
      {notice !== null && <Notice tone="info">{notice}</Notice>}
      {issued !== null && <IssuedKey issued={issued} onDone={() => setIssued(null)} />}
      {keys.state === "loading" && <p className="muted">Loading keys…</p>}
      {keys.state === "failed" && (
        <Notice tone="error">
          {keys.error.status === 404 ? `You have no project ${project} with an environment ${environment}.` : keys.error.message}
        </Notice>
      )}
      {keys.state === "loaded" && (
        <KeyTable
          keys={keys.data}
          project={project}
          environment={environment}
          onRevoked={(name) => {
            setNotice(`${name} is revoked. Flaglane refuses it from now on.`);
            keys.reload();
          }}
        />
      )}
      {issued === null && (
        <NewKey
          project={project}
          environment={environment}
          onIssued={(key) => {
            setNotice(null);
            setIssued(key);
            keys.reload();
          }}
        />
      )}
    </Layout>
  );
}

interface TableProps {
  readonly keys: readonly ApiKeyResponse[];
  readonly project: string;
  readonly environment: string;
  readonly onRevoked: (name: string) => void;
}

function KeyTable({ keys, project, environment, onRevoked }: TableProps) {
  const [confirming, setConfirming] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (keys.length === 0) {
    return <p className="muted">No keys yet.</p>;
  }

  const revoke = async (key: ApiKeyResponse) => {
    setBusy(true);
    setError(null);
    try {
      await api("DELETE /api/projects/{projectKey}/environments/{envKey}/keys/{keyId}", {
        projectKey: project,
        envKey: environment,
        keyId: key.id,
      });
      setConfirming(null);
      onRevoked(key.name);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Revoking the key failed.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      {error !== null && <Notice tone="error">{error}</Notice>}
      <table className="list keys">
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Type</th>
            <th scope="col">Key</th>
            <th scope="col">Issued</th>
            <th scope="col" title="Accurate to about a minute">
              Last used
            </th>
            <th scope="col">State</th>
          </tr>
        </thead>
        <tbody>
          {ordered(keys).map((key) => (
            <tr key={key.id} className={key.revokedAt === null ? undefined : "archived"}>
              <td>{key.name}</td>
              <td>{KEY_TYPES[key.type].label}</td>
              <td>
                <code title="The key's first characters, enough to tell keys apart. The rest is not stored.">
                  {key.prefix}…
                </code>
              </td>
              <td>{when.format(Date.parse(key.createdAt))}</td>
              <td>{key.lastUsedAt === null ? <span className="muted">Never</span> : when.format(Date.parse(key.lastUsedAt))}</td>
              <td>
                {key.revokedAt !== null ? (
                  <span className="badge">Revoked {when.format(Date.parse(key.revokedAt))}</span>
                ) : confirming === key.id ? (
                  <div className="confirm">
                    <p>{revokeWarning(key.name, environment)}</p>
                    <div className="actions">
                      <button type="button" className="danger" disabled={busy} onClick={() => revoke(key)}>
                        Revoke
                      </button>
                      <button type="button" disabled={busy} onClick={() => setConfirming(null)}>
                        Cancel
                      </button>
                    </div>
                  </div>
                ) : (
                  <>
                    <span className="badge on">Active</span>{" "}
                    <button type="button" onClick={() => setConfirming(key.id)}>
                      Revoke…
                    </button>
                  </>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

interface NewKeyProps {
  readonly project: string;
  readonly environment: string;
  readonly onIssued: (key: IssuedApiKeyResponse) => void;
}

function NewKey({ project, environment, onIssued }: NewKeyProps) {
  const [name, setName] = useState("");
  const [type, setType] = useState<KeyType>("server");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const issued = await api(
        "POST /api/projects/{projectKey}/environments/{envKey}/keys",
        { projectKey: project, envKey: environment },
        { name: name.trim(), type },
      );
      setName("");
      onIssued(issued);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Issuing the key failed.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="card" onSubmit={submit}>
      <h2>Issue a key</h2>
      <label>
        Name
        <input required maxLength={100} value={name} placeholder="checkout-service" onChange={(e) => setName(e.target.value)} />
        <span className="hint">Name it after what will use it, so you know what breaks when you revoke it.</span>
      </label>
      <fieldset>
        <legend>Type</legend>
        {(Object.keys(KEY_TYPES) as KeyType[]).map((option) => (
          <label className="inline key-type" key={option}>
            <input type="radio" name="type" checked={type === option} onChange={() => setType(option)} />
            <span>
              <strong>{KEY_TYPES[option].label}</strong> <span className="muted">— {KEY_TYPES[option].reads}</span>
            </span>
          </label>
        ))}
      </fieldset>
      {error !== null && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <div className="actions">
        <button type="submit" className="primary" disabled={busy || name.trim() === ""}>
          Issue key
        </button>
      </div>
    </form>
  );
}
