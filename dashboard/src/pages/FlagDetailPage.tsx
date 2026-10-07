import { useEffect, useState } from "react";
import { api, ApiError } from "../api/client";
import type { FlagConfigResponse, FlagResponse } from "../api/schema";
import { useLoad } from "../api/useLoad";
import { Layout } from "../components/Layout";
import { Notice } from "../components/Notice";
import { ConfigForm } from "../flags/ConfigForm";
import { KillSwitch } from "../flags/KillSwitch";
import { changes, isDirty, stagedFrom, type Staged } from "../flags/staging";
import { Link, path, useLeaveGuard } from "../router";

interface Props {
  readonly project: string;
  readonly environment: string;
  readonly flag: string;
}

const LEAVE = "This flag has unsaved changes. Leave without saving them?";

/** FR-UI-004 and FR-UI-007: one flag in one environment. */
export function FlagDetailPage({ project, environment, flag }: Props) {
  const loaded = useLoad(`${project}/${environment}/${flag}`, () =>
    Promise.all([
      api("GET /api/projects/{projectKey}/flags/{flagKey}", { projectKey: project, flagKey: flag }),
      api("GET /api/projects/{projectKey}/flags/{flagKey}/config/{envKey}", {
        projectKey: project,
        flagKey: flag,
        envKey: environment,
      }),
    ]),
  );

  return (
    <Layout project={{ key: project, environment }}>
      <p className="back">
        <Link to={path("projects", project, environment, "flags")}>← Flags in {environment}</Link>
      </p>
      {loaded.state === "loading" && <p className="muted">Loading…</p>}
      {loaded.state === "failed" && (
        <Notice tone="error">
          {loaded.error.status === 404
            ? `There is no flag ${flag} in ${environment} of ${project} among yours.`
            : loaded.error.message}
        </Notice>
      )}
      {loaded.state === "loaded" && (
        <Editor
          key={`${project}/${environment}/${flag}`}
          project={project}
          environment={environment}
          definition={loaded.data[0]}
          initial={loaded.data[1]}
        />
      )}
    </Layout>
  );
}

interface EditorProps {
  readonly project: string;
  readonly environment: string;
  readonly definition: FlagResponse;
  readonly initial: FlagConfigResponse;
}

function Editor({ project, environment, definition, initial }: EditorProps) {
  const [saved, setSaved] = useState(initial);
  const [staged, setStaged] = useState<Staged>(() => stagedFrom(initial));
  const [saving, setSaving] = useState<"form" | "switch" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const dirty = isDirty(saved, staged);
  useLeaveGuard(dirty ? LEAVE : null);

  useEffect(() => {
    if (notice === null) {
      return;
    }
    const clear = setTimeout(() => setNotice(null), 6000);
    return () => clearTimeout(clear);
  }, [notice]);

  const readOnly =
    definition.archivedAt === null ? null : "This flag is archived, so its configuration is read-only until it is restored.";
  const params = { projectKey: project, flagKey: definition.key, envKey: environment };

  const save = async () => {
    setSaving("form");
    setError(null);
    try {
      const updated = await api("PATCH /api/projects/{projectKey}/flags/{flagKey}/config/{envKey}", params, changes(saved, staged));
      setSaved(updated);
      setStaged(stagedFrom(updated));
      setNotice(`Saved. SDKs polling ${environment} pick it up within about five seconds.`);
    } catch (caught) {
      setError(failure(caught));
    } finally {
      setSaving(null);
    }
  };

  const setEnabled = async (enabled: boolean) => {
    setSaving("switch");
    setError(null);
    try {
      const updated = await api("PATCH /api/projects/{projectKey}/flags/{flagKey}/config/{envKey}", params, { enabled });
      // Only the kill switch was sent, so staged edits stay staged against the new saved state.
      setSaved(updated);
      setNotice(`${definition.key} is ${updated.enabled ? "on" : "off"} in ${environment}.`);
    } catch (caught) {
      setError(failure(caught));
    } finally {
      setSaving(null);
    }
  };

  return (
    <>
      <div className="heading">
        <h1>{definition.name}</h1>
        <code>{definition.key}</code>
        {definition.archivedAt !== null && <span className="badge">Archived</span>}
        {definition.clientSideVisible && <span className="badge" title="Readable with client keys">Client-side</span>}
      </div>
      {definition.description !== null && <p>{definition.description}</p>}
      {error !== null && <Notice tone="error">{error}</Notice>}
      {notice !== null && <Notice tone="info">{notice}</Notice>}
      <KillSwitch
        flag={definition.key}
        environment={environment}
        enabled={saved.enabled}
        busy={saving !== null}
        readOnly={readOnly}
        onSet={setEnabled}
      />
      {!saved.enabled && (
        <p className="muted">While the flag is off, these settings are kept and take effect when it is turned on.</p>
      )}
      <ConfigForm
        staged={staged}
        dirty={dirty}
        saving={saving === "form"}
        busy={saving !== null}
        readOnly={readOnly}
        onChange={setStaged}
        onSave={save}
        onDiscard={() => setStaged(stagedFrom(saved))}
      />
    </>
  );
}

function failure(caught: unknown): string {
  if (caught instanceof ApiError) {
    if (caught.status === 409) {
      return "This flag has been archived since the page loaded, so nothing was saved. Archived flags are read-only.";
    }
    return caught.message;
  }
  return "Saving failed.";
}
