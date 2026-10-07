import { useEffect } from "react";
import { api } from "../api/client";
import { useLoad } from "../api/useLoad";
import { Layout } from "../components/Layout";
import { Notice } from "../components/Notice";
import { lastEnvironment } from "../lastEnvironment";
import { navigate, path } from "../router";

/**
 * A project's address on its own opens its flags in an environment: the one last used in this
 * session, else development where there is one, the least dangerous place to land, else the first.
 */
export function ProjectHome({ project }: { project: string }) {
  const environments = useLoad(project, () =>
    api("GET /api/projects/{projectKey}/environments", { projectKey: project }),
  );

  useEffect(() => {
    if (environments.state !== "loaded") {
      return;
    }
    const keys = environments.data.map((environment) => environment.key);
    const last = lastEnvironment(project);
    const landing = last !== undefined && keys.includes(last) ? last : keys.includes("development") ? "development" : keys[0];
    if (landing !== undefined) {
      navigate(path("projects", project, landing, "flags"), { replace: true });
    }
  }, [environments, project]);

  return (
    <Layout project={{ key: project }}>
      {environments.state === "failed" && (
        <Notice tone="error">
          {environments.error.status === 404 ? `You have no project ${project}.` : environments.error.message}
        </Notice>
      )}
      {environments.state === "loaded" && environments.data.length === 0 && (
        <Notice tone="warning">This project has no environments. Create one through the API to add flags.</Notice>
      )}
      {environments.state === "loading" && <p className="muted">Loading…</p>}
    </Layout>
  );
}
