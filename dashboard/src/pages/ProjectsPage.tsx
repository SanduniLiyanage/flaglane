import { useState, type FormEvent } from "react";
import { api, ApiError } from "../api/client";
import { useLoad } from "../api/useLoad";
import { Layout } from "../components/Layout";
import { Notice } from "../components/Notice";
import { keyFrom, KEY_HINT, KEY_PATTERN } from "../keys";
import { Link, navigate } from "../router";

const date = new Intl.DateTimeFormat(undefined, { dateStyle: "medium" });

/** FR-UI-002: the projects you own, and a new one. */
export function ProjectsPage() {
  const projects = useLoad("projects", () => api("GET /api/projects", {}));

  return (
    <Layout>
      <h1>Projects</h1>
      {projects.state === "loading" && <p className="muted">Loading projects…</p>}
      {projects.state === "failed" && <Notice tone="error">{projects.error.message}</Notice>}
      {projects.state === "loaded" &&
        (projects.data.length === 0 ? (
          <p className="muted">
            No projects yet. A project holds your flags, with a development, a staging and a production
            environment to start with.
          </p>
        ) : (
          <table className="list">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Key</th>
                <th scope="col">Created</th>
              </tr>
            </thead>
            <tbody>
              {projects.data.map((project) => (
                <tr key={project.key}>
                  <td>
                    <Link to={`/projects/${encodeURIComponent(project.key)}`}>{project.name}</Link>
                  </td>
                  <td>
                    <code>{project.key}</code>
                  </td>
                  <td>{date.format(Date.parse(project.createdAt))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ))}
      <NewProject />
    </Layout>
  );
}

function NewProject() {
  const [name, setName] = useState("");
  const [key, setKey] = useState("");
  const [keyEdited, setKeyEdited] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const shownKey = keyEdited ? key : keyFrom(name);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const created = await api("POST /api/projects", {}, { key: shownKey, name: name.trim() });
      navigate(`/projects/${encodeURIComponent(created.key)}`);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught : new ApiError(0, "Creating the project failed."));
      setBusy(false);
    }
  };

  return (
    <form className="card" onSubmit={submit}>
      <h2>New project</h2>
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
        <span className="hint">{KEY_HINT} Unique across Flaglane, and it can never change.</span>
        {error?.fields.has("key") === true && <span className="field-error">{error.fields.get("key")}</span>}
      </label>
      {error !== null && (
        <p className="form-error" role="alert">
          {error.status === 409 ? "That key is taken. Choose another." : error.message}
        </p>
      )}
      <div className="actions">
        <button type="submit" className="primary" disabled={busy || shownKey === "" || name.trim() === ""}>
          Create project
        </button>
      </div>
    </form>
  );
}
