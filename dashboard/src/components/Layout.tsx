import type { ReactNode } from "react";
import { lastEnvironment, rememberEnvironment } from "../lastEnvironment";
import { confirmLeaving, Link, path } from "../router";
import { sessions, useSession } from "../session/session";
import { EnvironmentSwitcher } from "./EnvironmentSwitcher";

interface Props {
  /** The project the page is in, if any, for the breadcrumb and the environment switcher. */
  readonly project?: { readonly key: string; readonly environment?: string };
  /** The project page this is, for the tabs. */
  readonly tab?: "flags" | "keys" | "audit";
  readonly children: ReactNode;
}

export function Layout({ project, tab, children }: Props) {
  const { session } = useSession();
  if (project?.environment !== undefined) {
    rememberEnvironment(project.key, project.environment);
  }

  const signOut = () => {
    if (confirmLeaving()) {
      sessions.end("signed-out");
    }
  };

  return (
    <>
      <header className="top">
        <nav className="crumbs" aria-label="Location">
          <Link to="/projects" className="brand">
            Flaglane
          </Link>
          {project !== undefined && (
            <>
              <span aria-hidden="true">/</span>
              <Link to={`/projects/${encodeURIComponent(project.key)}`}>{project.key}</Link>
            </>
          )}
        </nav>
        {project?.environment !== undefined && (
          <EnvironmentSwitcher project={project.key} current={project.environment} />
        )}
        <div className="account">
          <span className="muted">{session?.email}</span>
          <button type="button" onClick={signOut} title="Discards your token in this tab">
            Sign out
          </button>
        </div>
      </header>
      {project !== undefined && tab !== undefined && <ProjectTabs project={project.key} environment={project.environment} tab={tab} />}
      <main className="page">{children}</main>
    </>
  );
}

interface TabsProps {
  readonly project: string;
  readonly environment: string | undefined;
  readonly tab: "flags" | "keys" | "audit";
}

/**
 * Flags and keys belong to an environment and the audit trail to the project; from the trail, the
 * other two open in the environment last used, or through the project's own landing page.
 */
function ProjectTabs({ project, environment, tab }: TabsProps) {
  const env = environment ?? lastEnvironment(project);
  const tabs = [
    { id: "flags", label: "Flags", to: env === undefined ? path("projects", project) : path("projects", project, env, "flags") },
    { id: "keys", label: "API keys", to: env === undefined ? path("projects", project) : path("projects", project, env, "keys") },
    { id: "audit", label: "Audit trail", to: path("projects", project, "audit") },
  ];
  return (
    <nav className="tabs" aria-label="Project">
      {tabs.map((t) => (
        <Link key={t.id} to={t.to} aria-current={t.id === tab ? "page" : undefined}>
          {t.label}
        </Link>
      ))}
    </nav>
  );
}
