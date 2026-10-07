import { useEffect, useState } from "react";
import { api, ApiError } from "../api/client";
import type { FlagConfigResponse, FlagResponse, OverrideRequest, RuleRequest } from "../api/schema";
import { useLoad } from "../api/useLoad";
import { Layout } from "../components/Layout";
import { Notice } from "../components/Notice";
import { ConfigForm } from "../flags/ConfigForm";
import { KillSwitch } from "../flags/KillSwitch";
import { draftsFrom, overridesChanged, toRequests, type OverrideDraft } from "../flags/overrides";
import { OverridesEditor } from "../flags/OverridesEditor";
import { draftFrom, ruleIndexOf, rulesChanged, toRequest, type RuleDraft } from "../flags/rules";
import { RulesEditor } from "../flags/RulesEditor";
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
  const loaded = useLoad(`${project}/${environment}/${flag}`, () => {
    const config = { projectKey: project, flagKey: flag, envKey: environment };
    return Promise.all([
      api("GET /api/projects/{projectKey}/flags/{flagKey}", { projectKey: project, flagKey: flag }),
      api("GET /api/projects/{projectKey}/flags/{flagKey}/config/{envKey}", config),
      api("GET /api/projects/{projectKey}/flags/{flagKey}/config/{envKey}/rules", config),
      api("GET /api/projects/{projectKey}/flags/{flagKey}/config/{envKey}/overrides", config),
    ]);
  });

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
          initialRules={loaded.data[2].rules}
          initialOverrides={loaded.data[3].overrides}
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
  readonly initialRules: readonly RuleRequest[];
  readonly initialOverrides: readonly OverrideRequest[];
}

type Saving = "form" | "switch" | "rules" | "overrides" | null;

function Editor({ project, environment, definition, initial, initialRules, initialOverrides }: EditorProps) {
  const [saved, setSaved] = useState(initial);
  const [staged, setStaged] = useState<Staged>(() => stagedFrom(initial));
  const [savedRules, setSavedRules] = useState(initialRules);
  const [rules, setRules] = useState<RuleDraft[]>(() => initialRules.map(draftFrom));
  const [refused, setRefused] = useState<ReadonlyMap<number, string>>(new Map());
  const [savedOverrides, setSavedOverrides] = useState(initialOverrides);
  const [overrides, setOverrides] = useState<OverrideDraft[]>(() => draftsFrom(initialOverrides));
  const [saving, setSaving] = useState<Saving>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const dirty = isDirty(saved, staged);
  const rulesDirty = rulesChanged(savedRules, rules);
  const overridesDirty = overridesChanged(savedOverrides, overrides);
  useLeaveGuard(dirty || rulesDirty || overridesDirty ? LEAVE : null);

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

  const saveRules = async () => {
    setSaving("rules");
    setError(null);
    setRefused(new Map());
    try {
      const stored = await api("PUT /api/projects/{projectKey}/flags/{flagKey}/config/{envKey}/rules", params, {
        rules: rules.map(toRequest),
      });
      setSavedRules(stored.rules);
      setRules(stored.rules.map(draftFrom));
      setNotice(`Rules saved. SDKs polling ${environment} pick them up within about five seconds.`);
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 400) {
        const byRule = new Map<number, string>();
        caught.fields.forEach((message, field) => {
          const index = ruleIndexOf(field);
          if (index !== null) {
            byRule.set(index, message);
          }
        });
        setRefused(byRule);
      }
      setError(failure(caught));
    } finally {
      setSaving(null);
    }
  };

  const saveOverrides = async () => {
    setSaving("overrides");
    setError(null);
    try {
      const stored = await api("PUT /api/projects/{projectKey}/flags/{flagKey}/config/{envKey}/overrides", params, {
        overrides: toRequests(overrides),
      });
      setSavedOverrides(stored.overrides);
      setOverrides(draftsFrom(stored.overrides));
      setNotice(`Overrides saved. SDKs polling ${environment} with a server key pick them up within about five seconds.`);
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
      <p className="muted">
        {saved.enabled ? "" : "While the flag is off, everything below is kept and takes effect when it is turned on. "}
        A user gets the value of the first of these that applies: a user override, the first rule that
        matches, the rollout, then the fallthrough value.
      </p>
      <OverridesEditor
        drafts={overrides}
        dirty={overridesDirty}
        saving={saving === "overrides"}
        busy={saving !== null}
        readOnly={readOnly}
        onChange={setOverrides}
        onSave={saveOverrides}
        onDiscard={() => setOverrides(draftsFrom(savedOverrides))}
      />
      <RulesEditor
        drafts={rules}
        dirty={rulesDirty}
        saving={saving === "rules"}
        busy={saving !== null}
        readOnly={readOnly}
        refused={refused}
        onChange={(next) => {
          setRules(next);
          setRefused(new Map());
        }}
        onSave={saveRules}
        onDiscard={() => {
          setRules(savedRules.map(draftFrom));
          setRefused(new Map());
        }}
      />
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
