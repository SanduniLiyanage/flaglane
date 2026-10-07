import type { ReactNode } from "react";
import { confirmLeaving, Link } from "../router";
import { sessions, useSession } from "../session/session";
import { EnvironmentSwitcher } from "./EnvironmentSwitcher";

interface Props {
  /** The project the page is in, if any, for the breadcrumb and the environment switcher. */
  readonly project?: { readonly key: string; readonly environment?: string };
  readonly children: ReactNode;
}

export function Layout({ project, children }: Props) {
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
      <main className="page">{children}</main>
    </>
  );
}
