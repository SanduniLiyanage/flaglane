import type { ReactNode } from "react";
import { confirmLeaving, Link, path } from "../router";
import { sessions, useSession } from "../session/session";
import { EnvironmentSwitcher } from "./EnvironmentSwitcher";

interface Props {
  /** The project the page is in, if any, for the breadcrumb and the environment switcher. */
  readonly project?: { readonly key: string; readonly environment?: string };
  /** The project page this is, for the tabs. */
  readonly tab?: "flags" | "keys";
  readonly children: ReactNode;
}

export function Layout({ project, tab, children }: Props) {
  const { session } = useSession();

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
      {project?.environment !== undefined && tab !== undefined && (
        <ProjectTabs project={project.key} environment={project.environment} tab={tab} />
      )}
      <main className="page">{children}</main>
    </>
  );
}

interface TabsProps {
  readonly project: string;
  readonly environment: string;
  readonly tab: "flags" | "keys";
}

/** A project's pages in one environment. */
function ProjectTabs({ project, environment, tab }: TabsProps) {
  const tabs = [
    { id: "flags", label: "Flags", to: path("projects", project, environment, "flags") },
    { id: "keys", label: "API keys", to: path("projects", project, environment, "keys") },
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
