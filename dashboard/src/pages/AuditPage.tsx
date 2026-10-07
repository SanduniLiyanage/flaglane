import { useEffect, useState } from "react";
import { api, ApiError } from "../api/client";
import type { AuditEntryResponse } from "../api/schema";
import { useLoad } from "../api/useLoad";
import { actionLabel, actorName, changesOf } from "../audit/present";
import { Layout } from "../components/Layout";
import { Notice } from "../components/Notice";
import { Link, navigate, path, useLocation } from "../router";

const PAGE = 50;
const when = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "medium" });

/**
 * FR-UI-006: a project's audit trail, newest first, a page at a time by keyset (FR-AUD-003),
 * narrowed to an environment or a flag through the address, so a narrowed trail can be linked to.
 */
export function AuditPage({ project }: { project: string }) {
  const location = useLocation();
  const query = new URLSearchParams(location.split("?")[1] ?? "");
  const environment = query.get("environment") ?? "";
  const flag = query.get("flag") ?? "";

  const choices = useLoad(`${project}/audit-choices`, () =>
    Promise.all([
      api("GET /api/projects/{projectKey}/environments", { projectKey: project }),
      api("GET /api/projects/{projectKey}/flags", { projectKey: project }),
    ]),
  );
  const first = useLoad(`${project}/audit/${environment}/${flag}`, () =>
    api("GET /api/projects/{projectKey}/audit", {
      projectKey: project,
      limit: PAGE,
      ...(environment === "" ? {} : { environment }),
      ...(flag === "" ? {} : { flag }),
    }),
  );
  const [older, setOlder] = useState<{ entries: AuditEntryResponse[]; next: string | null } | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreError, setMoreError] = useState<string | null>(null);

  // A new narrowing starts from its own first page.
  useEffect(() => {
    setOlder(null);
    setMoreError(null);
  }, [environment, flag]);

  const narrow = (change: { environment?: string; flag?: string }) => {
    const next = new URLSearchParams();
    const env = change.environment ?? environment;
    const fl = change.flag ?? flag;
    if (env !== "") next.set("environment", env);
    if (fl !== "") next.set("flag", fl);
    const search = next.toString();
    navigate(`${path("projects", project, "audit")}${search === "" ? "" : `?${search}`}`);
  };

  const entries = first.state === "loaded" ? [...first.data.entries, ...(older?.entries ?? [])] : [];
  const next = older !== null ? older.next : first.state === "loaded" ? first.data.next : null;

  const showOlder = async () => {
    if (next === null) return;
    setLoadingMore(true);
    setMoreError(null);
    try {
      const page = await api("GET /api/projects/{projectKey}/audit", {
        projectKey: project,
        limit: PAGE,
        before: next,
        ...(environment === "" ? {} : { environment }),
        ...(flag === "" ? {} : { flag }),
      });
      setOlder({ entries: [...(older?.entries ?? []), ...page.entries], next: page.next });
    } catch (caught) {
      setMoreError(caught instanceof ApiError ? caught.message : "Loading older entries failed.");
    } finally {
      setLoadingMore(false);
    }
  };

  return (
    <Layout project={{ key: project }} tab="audit">
      <h1>Audit trail</h1>
      <p className="muted">
        Every change to this project, newest first, with the state before and after as Flaglane recorded it. A
        rollout is recorded in basis points, hundredths of a percent, as it is stored: 3000 basis points is
        30%. Entries cannot be edited or removed, by anyone.
      </p>
      <div className="filters">
        <label>
          Environment
          <select value={environment} onChange={(e) => narrow({ environment: e.target.value })}>
            <option value="">All environments</option>
            {choices.state === "loaded" &&
              choices.data[0].map((env) => (
                <option key={env.key} value={env.key}>
                  {env.key}
                </option>
              ))}
            {environment !== "" &&
              choices.state === "loaded" &&
              !choices.data[0].some((env) => env.key === environment) && <option value={environment}>{environment}</option>}
          </select>
        </label>
        <label>
          Flag
          <select value={flag} onChange={(e) => narrow({ flag: e.target.value })}>
            <option value="">All flags, and changes to no flag</option>
            {choices.state === "loaded" &&
              choices.data[1].map((f) => (
                <option key={f.key} value={f.key}>
                  {f.key}
                  {f.archivedAt === null ? "" : " (archived)"}
                </option>
              ))}
          </select>
        </label>
      </div>
      {first.state === "loading" && <p className="muted">Loading the trail…</p>}
      {first.state === "failed" && (
        <Notice tone="error">
          {first.error.status === 404
            ? `There is no ${environment !== "" ? `environment ${environment}` : ""}${environment !== "" && flag !== "" ? " or " : ""}${flag !== "" ? `flag ${flag}` : ""} in ${project} among yours.`
            : first.error.message}
        </Notice>
      )}
      {first.state === "loaded" && entries.length === 0 && <p className="muted">Nothing has been recorded here yet.</p>}
      {entries.length > 0 && (
        <ol className="audit">
          {entries.map((entry) => (
            <Entry key={entry.id} entry={entry} project={project} />
          ))}
        </ol>
      )}
      {moreError !== null && <Notice tone="error">{moreError}</Notice>}
      {first.state === "loaded" && (
        <p className="muted">
          {entries.length} {entries.length === 1 ? "entry" : "entries"}
          {next === null ? ", the whole trail." : " shown. "}
          {next !== null && (
            <button type="button" onClick={showOlder} disabled={loadingMore}>
              {loadingMore ? "Loading…" : "Show older entries"}
            </button>
          )}
        </p>
      )}
    </Layout>
  );
}

function Entry({ entry, project }: { entry: AuditEntryResponse; project: string }) {
  const changes = changesOf(entry);
  const flagLink =
    entry.flag !== null && entry.environment !== null && !entry.environmentDeleted
      ? path("projects", project, entry.environment, "flags", entry.flag)
      : null;
  return (
    <li className="card audit-entry">
      <div className="audit-head">
        <time dateTime={entry.createdAt}>{when.format(Date.parse(entry.createdAt))}</time>
        <span>
          <strong>{actorName(entry)}</strong> {actionLabel(entry.action)}
        </span>
        <span className="audit-scope">
          {entry.environment !== null && (
            <span className="badge" title={entry.environmentDeleted ? "This environment has since been deleted" : undefined}>
              {entry.environment}
              {entry.environmentDeleted ? " (deleted)" : ""}
            </span>
          )}
          {entry.flag !== null &&
            (flagLink !== null ? (
              <Link to={flagLink}>
                <code>{entry.flag}</code>
              </Link>
            ) : (
              <code>{entry.flag}</code>
            ))}
          <code className="muted">{entry.action}</code>
        </span>
      </div>
      {changes.length > 0 && (
        <table className="changes">
          <thead>
            <tr>
              <th scope="col">Recorded field</th>
              {entry.previousValue !== null && <th scope="col">Before</th>}
              {entry.newValue !== null && <th scope="col">After</th>}
            </tr>
          </thead>
          <tbody>
            {changes.map((change) => (
              <tr key={change.field}>
                <td>
                  <code>{change.field}</code>
                </td>
                {entry.previousValue !== null && <td>{change.before}</td>}
                {entry.newValue !== null && <td>{change.after}</td>}
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <details>
        <summary>As recorded</summary>
        <pre>{JSON.stringify({ previousValue: entry.previousValue, newValue: entry.newValue }, null, 2)}</pre>
      </details>
    </li>
  );
}
