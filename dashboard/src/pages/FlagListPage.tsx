import { useState, type FormEvent } from "react";
import { api, ApiError } from "../api/client";
import type { FlagConfigResponse, FlagResponse } from "../api/schema";
import { useLoad } from "../api/useLoad";
import { Layout } from "../components/Layout";
import { Notice } from "../components/Notice";
import { keyFrom, KEY_HINT, KEY_PATTERN } from "../keys";
import { Link, navigate, path } from "../router";

interface Row {
  readonly flag: FlagResponse;
  readonly config: FlagConfigResponse | undefined;
}

/** FR-UI-003: every flag of the project, with its state in one environment. */
export function FlagListPage({ project, environment }: { project: string; environment: string }) {
  const [showArchived, setShowArchived] = useState(false);
  const rows = useLoad(`${project}/${environment}`, async (): Promise<Row[]> => {
    const [flags, configs] = await Promise.all([
      api("GET /api/projects/{projectKey}/flags", { projectKey: project }),
      api("GET /api/projects/{projectKey}/environments/{envKey}/configs", {
        projectKey: project,
        envKey: environment,
      }),
    ]);
    const byKey = new Map(configs.map((config) => [config.flagKey, config]));
    return flags
      .map((flag) => ({ flag, config: byKey.get(flag.key) }))
      .sort((a, b) => a.flag.key.localeCompare(b.flag.key));
  });

  const archived = rows.state === "loaded" ? rows.data.filter((row) => row.flag.archivedAt !== null).length : 0;
  const shown =
    rows.state === "loaded" ? rows.data.filter((row) => showArchived || row.flag.archivedAt === null) : [];

  return (
    <Layout project={{ key: project, environment }} tab="flags">
      <div className="heading">
        <h1>Flags</h1>
        <span className="muted">in {environment}</span>
      </div>
      {rows.state === "loading" && <p className="muted">Loading flags…</p>}
      {rows.state === "failed" && (
        <Notice tone="error">
          {rows.error.status === 404
            ? `You have no project ${project} with an environment ${environment}.`
            : rows.error.message}
        </Notice>
      )}
      {rows.state === "loaded" && (
        <>
          {shown.length === 0 ? (
            <p className="muted">
              {archived > 0 && !showArchived
                ? "Every flag in this project is archived."
                : "No flags yet. A new flag starts off, in every environment."}
            </p>
          ) : (
            <table className="list">
              <thead>
                <tr>
                  <th scope="col">Key</th>
                  <th scope="col">Name</th>
                  <th scope="col">State</th>
                  <th scope="col">Rollout</th>
                </tr>
              </thead>
              <tbody>
                {shown.map(({ flag, config }) => (
                  <tr key={flag.key} className={flag.archivedAt === null ? undefined : "archived"}>
                    <td>
                      <Link to={path("projects", project, environment, "flags", flag.key)}>
                        <code>{flag.key}</code>
                      </Link>
                    </td>
                    <td>{flag.name}</td>
                    <td>
                      {flag.archivedAt !== null ? (
                        <span className="badge">Archived</span>
                      ) : config === undefined ? (
                        <span className="muted">No configuration</span>
                      ) : (
                        <span className={config.enabled ? "badge on" : "badge off"}>{config.enabled ? "On" : "Off"}</span>
                      )}
                    </td>
                    <td>{config === undefined ? "" : <Rollout config={config} />}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {archived > 0 && (
            <label className="inline">
              <input type="checkbox" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} />
              Show archived flags ({archived})
            </label>
          )}
        </>
      )}
      <NewFlag project={project} environment={environment} />
    </Layout>
  );
}

function Rollout({ config }: { config: FlagConfigResponse }) {
  if (config.fallthroughValue) {
    return (
      <span title="The fallthrough value is true, so everyone the rollout does not reach gets true anyway">
        {config.rolloutPercentage}% <span className="muted">· falls through to true</span>
      </span>
    );
  }
  return <>{config.rolloutPercentage}%</>;
}

function NewFlag({ project, environment }: { project: string; environment: string }) {
  const [name, setName] = useState("");
  const [key, setKey] = useState("");
  const [keyEdited, setKeyEdited] = useState(false);
  const [description, setDescription] = useState("");
  const [clientSideVisible, setClientSideVisible] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const shownKey = keyEdited ? key : keyFrom(name);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const created = await api(
        "POST /api/projects/{projectKey}/flags",
        { projectKey: project },
        {
          key: shownKey,
          name: name.trim(),
          ...(description.trim() === "" ? {} : { description: description.trim() }),
          clientSideVisible,
        },
      );
      navigate(path("projects", project, environment, "flags", created.key));
    } catch (caught) {
      setError(caught instanceof ApiError ? caught : new ApiError(0, "Creating the flag failed."));
      setBusy(false);
    }
  };

  return (
    <form className="card" onSubmit={submit}>
      <h2>New flag</h2>
      <p className="muted">It starts off in every environment, falling through to false, at 0%.</p>
      <label>
        Name
        <input required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
      </label>
      <label>
        Key
        <input
          required
          pattern={KEY_PATTERN}
          value={shownKey}
          onChange={(e) => {
            setKeyEdited(true);
            setKey(e.target.value);
          }}
          aria-invalid={error?.fields.has("key") ?? false}
        />
        <span className="hint">
          {KEY_HINT} Your code refers to the flag by its key, so it can never change, and an archived
          flag keeps its key.
        </span>
        {error?.fields.has("key") === true && <span className="field-error">{error.fields.get("key")}</span>}
      </label>
      <label>
        <span>
          Description <span className="muted">(optional)</span>
        </span>
        <textarea maxLength={1000} rows={2} value={description} onChange={(e) => setDescription(e.target.value)} />
      </label>
      <label className="inline">
        <input type="checkbox" checked={clientSideVisible} onChange={(e) => setClientSideVisible(e.target.checked)} />
        Readable with client keys
      </label>
      <span className="hint">
        Client keys are public: anyone can read the rules of a flag they can read. Leave this off for a
        flag only your servers evaluate.
      </span>
      {error !== null && (
        <p className="form-error" role="alert">
          {error.status === 409 ? "A flag in this project, live or archived, already has that key." : error.message}
        </p>
      )}
      <div className="actions">
        <button type="submit" className="primary" disabled={busy || shownKey === "" || name.trim() === ""}>
          Create flag
        </button>
      </div>
    </form>
  );
}
